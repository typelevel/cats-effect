/*
 * Copyright 2020-2021 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * The pool management code in this class is heavily inspired by the `worker`
 * and `idle` code from the `tokio` runtime. The original source code in Rust is
 * licensed under the MIT license and available at:
 * https://docs.rs/crate/tokio/0.2.22/source/src/runtime/thread_pool/worker.rs
 * and
 * https://docs.rs/crate/tokio/0.2.22/source/src/runtime/thread_pool/idle.rs.
 *
 * For the design decisions behind the `tokio` runtime, please consult the
 * following resource:
 * https://tokio.rs/blog/2019-10-scheduler.
 */

package cats.effect
package unsafe

import scala.concurrent.ExecutionContext

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.locks.LockSupport

/**
 * Work-stealing thread pool which manages a pool of [[WorkerThread]] s for the specific purpose
 * of executing [[cats.effect.IOFiber]] instancess with work-stealing scheduling semantics.
 *
 * The thread pool starts with `threadCount` worker threads in the active state, looking to find
 * fibers to execute in their own local work stealing queues, or externally scheduled work
 * coming from the overflow queue.
 *
 * In the case that a worker thread cannot find work to execute in its own queue, or the
 * external queue, it asks for permission from the pool to enter the searching state, which
 * allows that thread to try and steal work from the other worker threads. The pool tries to
 * maintain at most `threadCount / 2` worker threads which are searching for work, to reduce
 * contention. Work stealing is tried using a linear search starting from a random worker thread
 * index.
 */
private[effect] final class WorkStealingThreadPool(
    threadCount: Int, // number of worker threads
    threadPrefix: String, // prefix for the name of worker threads
    self0: => IORuntime
) extends ExecutionContext {

  import WorkStealingThreadPoolConstants._

  /**
   * A forward reference to the [[cats.effect.unsafe.IORuntime]] of which this thread pool is a
   * part. Used for starting fibers in [[WorkStealingThreadPool#execute]].
   */
  private[this] lazy val self: IORuntime = self0

  /**
   * References to worker threads and their local queues.
   */
  private[this] val workerThreads: Array[WorkerThread] = new Array(threadCount)
  private[this] val localQueues: Array[LocalQueue] = new Array(threadCount)
  private[this] val parkedSignals: Array[AtomicBoolean] = new Array(threadCount)

  /**
   * References to helper threads. Helper threads must have a parking mechanism to address the
   * situation where all worker threads are executing blocking actions, but the pool has been
   * completely exhausted and the work that will unblock the worker threads has not arrived on
   * the pool yet. Obviously, the helper threads cannot busy wait in this case, so they need to
   * park and await a notification of newly arrived work. This queue also helps with the
   * thundering herd problem. Namely, when only a single unit of work arrives and needs to be
   * executed by a helper thread because all worker threads are blocked, only a single helper
   * thread can be woken up. That thread can wake other helper threads in the future as the work
   * available on the pool increases.
   */
  private[this] val helperThreads: ScalQueue[HelperThread] = new ScalQueue(threadCount)

  /**
   * The batched queue on which spillover work from other local queues can end up.
   */
  private[this] val batchedQueue: ScalQueue[Array[IOFiber[_]]] =
    new ScalQueue(threadCount)

  /**
   * The overflow queue on which fibers coming from outside the pool are enqueued.
   */
  private[this] val overflowQueue: ScalQueue[IOFiber[_]] =
    new ScalQueue(threadCount)

  /**
   * Represents two unsigned 16 bit integers. The 16 most significant bits track the number of
   * active (unparked) worker threads. The 16 least significant bits track the number of worker
   * threads that are searching for work to steal from other worker threads.
   */
  private[this] val state: AtomicInteger = new AtomicInteger(threadCount << UnparkShift)

  /**
   * An atomic counter used for generating unique indices for distinguishing and naming helper
   * threads.
   */
  private[this] val blockingThreadCounter: AtomicInteger = new AtomicInteger(0)

  /**
   * The shutdown latch of the work stealing thread pool.
   */
  private[this] val done: AtomicBoolean = new AtomicBoolean(false)

  // Thread pool initialization block.
  {
    // Set up the worker threads.
    var i = 0
    while (i < threadCount) {
      val queue = new LocalQueue()
      localQueues(i) = queue
      val parkedSignal = new AtomicBoolean(false)
      parkedSignals(i) = parkedSignal
      val index = i
      val thread =
        new WorkerThread(
          index,
          threadPrefix,
          blockingThreadCounter,
          queue,
          parkedSignal,
          batchedQueue,
          overflowQueue,
          this)
      workerThreads(i) = thread
      i += 1
    }

    // Start the worker threads.
    i = 0
    while (i < threadCount) {
      workerThreads(i).start()
      i += 1
    }
  }

  /**
   * Tries to steal work from other worker threads. This method does a linear search of the
   * worker threads starting at a random index. If the stealing attempt was unsuccessful, this
   * method falls back to checking the overflow queue.
   *
   * @param dest
   *   the index of the worker thread attempting to steal work from other worker threads (used
   *   to avoid stealing from its own local queue)
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their enqueue operations
   * @return
   *   a fiber instance to execute instantly in case of a successful steal
   */
  private[unsafe] def stealFromOtherWorkerThread(
      dest: Int,
      random: ThreadLocalRandom): IOFiber[_] = {
    val destQueue = localQueues(dest)
    val from = random.nextInt(threadCount)

    var i = 0
    while (i < threadCount) {
      // Compute the index of the thread to steal from.
      val index = (from + i) % threadCount

      if (index != dest) {
        // Do not steal from yourself.
        val res = localQueues(index).stealInto(destQueue)
        if (res != null) {
          // Successful steal. Return the next fiber to be executed.
          return res
        }
      }

      i += 1
    }

    // The worker thread could not steal any work. Fall back to checking the external queue.
    overflowQueue.poll(random)
  }

  /**
   * Potentially unparks a worker thread.
   *
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their enqueue operations
   */
  private[unsafe] def notifyParked(random: ThreadLocalRandom): Boolean = {
    // Find a worker thread to unpark.
    if (!notifyShouldWakeup()) {
      // There are enough searching and/or running worker threads.
      // Unparking a new thread would probably result in unnecessary contention.
      return false
    }

    // Unpark a worker thread.
    val from = random.nextInt(threadCount)
    var i = 0
    while (i < threadCount) {
      val index = (from + i) % threadCount

      val signal = parkedSignals(index)
      if (signal.getAndSet(false)) {
        // Update the state so that a thread can be unparked.
        // Here we are updating the 16 most significant bits, which hold the
        // number of active threads, as well as incrementing the number of
        // searching worker threads (unparked worker threads are implicitly
        // allowed to search for work in the local queues of other worker
        // threads).
        state.getAndAdd(DeltaSearching)
        val worker = workerThreads(index)
        LockSupport.unpark(worker)
        return true
      }

      i += 1
    }

    false
  }

  /**
   * Potentially unparks a helper thread.
   *
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their operations
   */
  private[unsafe] def notifyHelper(random: ThreadLocalRandom): Unit = {
    val helper = helperThreads.poll(random)
    if (helper ne null) {
      helper.unpark()
      LockSupport.unpark(helper)
    }
  }

  /**
   * Checks the number of active and searching worker threads and decides whether another thread
   * should be notified of new work.
   *
   * Should wake up another worker thread when there are 0 searching threads and fewer than
   * `threadCount` active threads.
   *
   * @return
   *   `true` if a parked worker thread should be woken up, `false` otherwise
   */
  private[this] def notifyShouldWakeup(): Boolean = {
    val st = state.get()
    (st & SearchMask) == 0 && ((st & UnparkMask) >>> UnparkShift) < threadCount
  }

  /**
   * Notifies a thread if there are fibers available for stealing in any of the local queues, or
   * in the overflow queue.
   *
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their enqueue operations
   */
  private[unsafe] def notifyIfWorkPending(random: ThreadLocalRandom): Unit = {
    var i = 0
    while (i < threadCount) {
      // Check each worker thread for available work that can be stolen.
      if (localQueues(i).nonEmpty()) {
        notifyParked(random)
        return
      }
      i += 1
    }

    // If no work was found in the local queues of the worker threads, look for
    // work in the batched queue.
    if (batchedQueue.nonEmpty() && !notifyParked(random)) {
      notifyHelper(random)
      return
    }

    // If no work was found in the local queues of the worker threads or in the
    // batched queue, look for work in the external queue.
    if (overflowQueue.nonEmpty() && !notifyParked(random)) {
      notifyHelper(random)
    }
  }

  /**
   * Decides whether a worker thread is allowed to enter the searching state where it can look
   * for work to steal from other worker threads.
   *
   * @return
   *   `true` if the requesting worker thread should be allowed to search for work to steal from
   *   other worker threads, `false` otherwise
   */
  private[unsafe] def transitionWorkerToSearching(): Boolean = {
    val st = state.get()

    // Try to keep at most around 50% threads that are searching for work, to
    // reduce unnecessary contention. It is not exactly 50%, but it is a good
    // enough approximation.
    if (2 * (st & SearchMask) >= threadCount) {
      // There are enough searching worker threads. Do not allow this thread to
      // enter the searching state.
      false
    } else {
      // Allow this thread to enter the searching state (increment the number of
      // searching threads).
      state.getAndIncrement()
      true
    }
  }

  /**
   * Deregisters the current worker thread from the set of searching threads and asks for help
   * with the local queue if necessary.
   *
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their enqueue operations
   */
  private[unsafe] def transitionWorkerFromSearching(random: ThreadLocalRandom): Unit = {
    // Decrement the number of searching worker threads.
    val prev = state.getAndDecrement()
    if (prev == 1) {
      // If this was the only searching thread, wake a thread up to potentially help out
      // with the local work queue.
      notifyParked(random)
      ()
    }
  }

  /**
   * Updates the internal state to mark the given worker thread as parked.
   *
   * @return
   *   `true` if the given worker thread was the last searching thread, `false` otherwise
   */
  private[unsafe] def transitionWorkerToParkedWhenSearching(): Boolean = {
    // Decrement the number of unparked and searching threads simultaneously
    // since this thread was searching prior to parking.
    val prev = state.getAndAdd(-DeltaSearching)
    (prev & SearchMask) == 1
  }

  /**
   * Updates the internal state to mark the given worker thread as parked.
   *
   * @note
   *   This method is intentionally duplicated, to accomodate the unconditional code paths in
   *   the [[WorkerThread]] runloop.
   */
  private[unsafe] def transitionWorkerToParked(): Unit = {
    // Decrement the number of unparked threads only.
    state.getAndAdd(-DeltaNotSearching)
    ()
  }

  /**
   * Enqueues the provided helper thread on the queue of parked helper threads.
   *
   * @param helper
   *   the helper thread to enqueue on the queue of parked threads
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their operations
   */
  private[unsafe] def transitionHelperToParked(
      helper: HelperThread,
      random: ThreadLocalRandom): Unit = {
    helperThreads.offer(helper, random)
  }

  /**
   * Removes the provided helper thread from the parked helper thread queue.
   *
   * This method is necessary for the situation when a worker/helper thread has finished
   * executing the blocking actions and needs to signal its helper thread to end. At that point
   * in time, the helper thread might be parked and enqueued. Furthermore, this method signals
   * to other worker and helper threads that there could still be some leftover work on the pool
   * and that they need to replace the exiting helper thread.
   *
   * @param helper
   *   the helper thread to remove from the queue of parked threads
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their operations
   */
  private[unsafe] def removeParkedHelper(
      helper: HelperThread,
      random: ThreadLocalRandom): Unit = {
    helperThreads.remove(helper)
    if (!notifyParked(random)) {
      notifyHelper(random)
    }
  }

  /**
   * Schedules a fiber on this thread pool.
   *
   * If the request comes from a [[WorkerThread]], the fiber is enqueued on the local queue of
   * that thread.
   *
   * If the request comes from a [[HelperTread]] or an external thread, the fiber is enqueued on
   * the overflow queue. Furthermore, if the request comes from an external thread, worker
   * threads are notified of new work.
   *
   * @param fiber
   *   the fiber to be executed on the thread pool
   */
  private[effect] def rescheduleFiber(fiber: IOFiber[_]): Unit = {
    val pool = this
    val thread = Thread.currentThread()

    if (thread.isInstanceOf[WorkerThread]) {
      val worker = thread.asInstanceOf[WorkerThread]
      if (worker.isOwnedBy(pool)) {
        worker.reschedule(fiber)
      } else {
        scheduleExternal(fiber)
      }
    } else if (thread.isInstanceOf[HelperThread]) {
      val helper = thread.asInstanceOf[HelperThread]
      if (helper.isOwnedBy(pool)) {
        helper.schedule(fiber)
      } else {
        scheduleExternal(fiber)
      }
    } else {
      scheduleExternal(fiber)
    }
  }

  /**
   * Reschedules a fiber on this thread pool.
   *
   * If the request comes from a [[WorkerThread]], depending on the current load, the fiber can
   * be scheduled for immediate execution on the worker thread, potentially bypassing the local
   * queue and reducing the stealing pressure.
   *
   * If the request comes from a [[HelperTread]] or an external thread, the fiber is enqueued on
   * the overflow queue. Furthermore, if the request comes from an external thread, worker
   * threads are notified of new work.
   *
   * @param fiber
   *   the fiber to be executed on the thread pool
   */
  private[effect] def scheduleFiber(fiber: IOFiber[_]): Unit = {
    val pool = this
    val thread = Thread.currentThread()

    if (thread.isInstanceOf[WorkerThread]) {
      val worker = thread.asInstanceOf[WorkerThread]
      if (worker.isOwnedBy(pool)) {
        worker.schedule(fiber)
      } else {
        scheduleExternal(fiber)
      }
    } else if (thread.isInstanceOf[HelperThread]) {
      val helper = thread.asInstanceOf[HelperThread]
      if (helper.isOwnedBy(pool)) {
        helper.schedule(fiber)
      } else {
        scheduleExternal(fiber)
      }
    } else {
      scheduleExternal(fiber)
    }
  }

  /**
   * Schedules a fiber for execution on this thread pool originating from an external thread (a
   * thread which is not owned by this thread pool).
   *
   * @param fiber
   *   the fiber to be executed on the thread pool
   */
  private[this] def scheduleExternal(fiber: IOFiber[_]): Unit = {
    val random = ThreadLocalRandom.current()
    overflowQueue.offer(fiber, random)
    if (!notifyParked(random)) {
      notifyHelper(random)
    }
  }

  /**
   * Executes a [[java.lang.Runnable]] on the [[WorkStealingThreadPool]].
   *
   * If the submitted `runnable` is a general purpose computation, it is suspended in
   * [[cats.effect.IO]] and executed as a fiber on this pool.
   *
   * On the other hand, if the submitted `runnable` is an instance of [[cats.effect.IOFiber]],
   * it is directly executed on this pool without any wrapping or indirection. This
   * functionality is used as a fast path in the [[cats.effect.IOFiber]] runloop for quick
   * scheduling of fibers which are resumed on the thread pool as part of the asynchronous node
   * of [[cats.effect.IO]].
   *
   * This method fulfills the `ExecutionContext` interface.
   *
   * @param runnable
   *   the runnable to be executed
   */
  override def execute(runnable: Runnable): Unit = {
    if (runnable.isInstanceOf[IOFiber[_]]) {
      // Fast-path scheduling of a fiber without wrapping.
      scheduleFiber(runnable.asInstanceOf[IOFiber[_]])
    } else {
      // Executing a general purpose computation on the thread pool.
      // Wrap the runnable in an `IO` and execute it as a fiber.
      val io = IO.delay(runnable.run())
      val fiber = new IOFiber[Unit](0, Map.empty, outcomeToUnit, io, this, self)
      scheduleFiber(fiber)
    }
  }

  /**
   * Preallocated fiber callback function for transforming [[java.lang.Runnable]] values into
   * [[cats.effect.IOFiber]] instances.
   */
  private[this] val outcomeToUnit: OutcomeIO[Unit] => Unit = {
    case Outcome.Errored(t) => reportFailure(t)
    case _ => ()
  }

  /**
   * Reports unhandled exceptions and errors by printing them to the error stream.
   *
   * This method fulfills the `ExecutionContext` interface.
   *
   * @param cause
   *   the unhandled throwable instances
   */
  override def reportFailure(cause: Throwable): Unit = {
    cause.printStackTrace()
  }

  /**
   * Shut down the thread pool and clean up the pool state. Calling this method after the pool
   * has been shut down has no effect.
   */
  def shutdown(): Unit = {
    // Execute the shutdown logic only once.
    if (done.compareAndSet(false, true)) {
      // Send an interrupt signal to each of the worker threads.

      // Note: while loops and mutable variables are used throughout this method
      // to avoid allocations of objects, since this method is expected to be
      // executed mostly in situations where the thread pool is shutting down in
      // the face of unhandled exceptions or as part of the whole JVM exiting.
      var i = 0
      while (i < threadCount) {
        workerThreads(i).interrupt()
        i += 1
      }

      // Wait for all worker threads to finalize.
      // Clear the interrupt flag.
      Thread.interrupted()

      // Check if a worker thread is shutting down the thread pool, to avoid a
      // self join, which hangs forever. Any other thread shutting down the pool
      // will receive an index of `-1`, which means that it will join all worker
      // threads.
      val thread = Thread.currentThread()
      val workerIndex = if (thread.isInstanceOf[WorkerThread]) {
        thread.asInstanceOf[WorkerThread].index
      } else {
        -1
      }

      i = 0
      while (i < threadCount) {
        if (workerIndex != i) {
          workerThreads(i).join()
        }
        i += 1
      }

      // It is now safe to clean up the state of the thread pool.
      state.lazySet(0)

      // Shutdown and drain the external queue.
      overflowQueue.clear()
      Thread.currentThread().interrupt()
    }
  }
}
