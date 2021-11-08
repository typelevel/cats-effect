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

import cats.effect.tracing.TracingConstants

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.locks.LockSupport

/**
 * Work-stealing thread pool which manages a pool of [[WorkerThread]] s for the specific purpose
 * of executing [[cats.effect.IOFiber]] instancess with work-stealing scheduling semantics.
 *
 * The thread pool starts with `threadCount` worker threads in the active state, looking to find
 * fibers to execute in their own local work stealing queues, or externally scheduled work
 * coming from the external queue.
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

  import TracingConstants._
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
   * Atomic variable for used for publishing changes to the references in the `workerThreads`
   * array. Worker threads can be changed whenever blocking code is encountered on the pool.
   * When a worker thread is about to block, it spawns a new worker thread that would replace
   * it, transfers the local queue to it and proceeds to run the blocking code, after which it
   * exits.
   */
  private[this] val workerThreadPublisher: AtomicBoolean = new AtomicBoolean(false)

  private[this] val externalQueue: ScalQueue[AnyRef] =
    new ScalQueue(threadCount << 2)

  /**
   * Represents two unsigned 16 bit integers. The 16 most significant bits track the number of
   * active (unparked) worker threads. The 16 least significant bits track the number of worker
   * threads that are searching for work to steal from other worker threads.
   */
  private[this] val state: AtomicInteger = new AtomicInteger(threadCount << UnparkShift)

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
      val fiberBag = new WeakHashMap[AnyRef, WeakReference[IOFiber[_]]]()
      val thread =
        new WorkerThread(
          index,
          threadPrefix,
          queue,
          parkedSignal,
          externalQueue,
          null,
          fiberBag,
          this)
      workerThreads(i) = thread
      i += 1
    }

    // Publish the worker threads.
    workerThreadPublisher.set(true)

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
   * method falls back to checking the external queue.
   *
   * @param dest
   *   the index of the worker thread attempting to steal work from other worker threads (used
   *   to avoid stealing from its own local queue)
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their enqueue operations
   * @param destWorker
   *   a reference to the destination worker thread, used for setting the active fiber reference
   * @return
   *   a fiber instance to execute instantly in case of a successful steal
   */
  private[unsafe] def stealFromOtherWorkerThread(
      dest: Int,
      random: ThreadLocalRandom,
      destWorker: WorkerThread): IOFiber[_] = {
    val destQueue = localQueues(dest)
    val from = random.nextInt(threadCount)

    var i = 0
    while (i < threadCount) {
      // Compute the index of the thread to steal from.
      val index = (from + i) % threadCount

      if (index != dest) {
        // Do not steal from yourself.
        val res = localQueues(index).stealInto(destQueue, destWorker)
        if (res != null) {
          // Successful steal. Return the next fiber to be executed.
          return res
        }
      }

      i += 1
    }

    // The worker thread could not steal any work. Fall back to checking the
    // external queue.
    val element = externalQueue.poll(random)
    if (element.isInstanceOf[Array[IOFiber[_]]]) {
      val batch = element.asInstanceOf[Array[IOFiber[_]]]
      destQueue.enqueueBatch(batch, destWorker)
    } else if (element.isInstanceOf[IOFiber[_]]) {
      val fiber = element.asInstanceOf[IOFiber[_]]

      if (isStackTracing) {
        destWorker.active = fiber
        parkedSignals(dest).lazySet(false)
      }

      fiber
    } else {
      null
    }
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
        // Fetch the latest references to the worker threads before doing the
        // actual unparking. There is no danger of a race condition where the
        // parked signal has been successfully marked as unparked but the
        // underlying worker thread reference has changed, because a worker thread
        // can only be replaced right before executing blocking code, at which
        // point it is already unparked and entering this code region is thus
        // impossible.
        workerThreadPublisher.get()
        val worker = workerThreads(index)
        LockSupport.unpark(worker)
        return true
      }

      i += 1
    }

    false
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
   * in the external queue.
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
    // work in the external queue.
    if (externalQueue.nonEmpty()) {
      notifyParked(random)
      ()
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
   * Replaces the blocked worker thread with the provided index with a clone.
   *
   * @param index
   *   the worker thread index at which the replacement will take place
   * @param newWorker
   *   the new worker thread instance to be installed at the provided index
   */
  private[unsafe] def replaceWorker(index: Int, newWorker: WorkerThread): Unit = {
    workerThreads(index) = newWorker
    workerThreadPublisher.lazySet(true)
  }

  /**
   * Reschedules a fiber on this thread pool.
   *
   * If the request comes from a [[WorkerThread]], the fiber is enqueued on the local queue of
   * that thread.
   *
   * If the request comes from a [[HelperTread]] or an external thread, the fiber is enqueued on
   * the external queue. Furthermore, if the request comes from an external thread, worker
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
    } else {
      scheduleExternal(fiber)
    }
  }

  /**
   * Schedules a fiber on this thread pool.
   *
   * If the request comes from a [[WorkerThread]], depending on the current load, the fiber can
   * be scheduled for immediate execution on the worker thread, potentially bypassing the local
   * queue and reducing the stealing pressure. In case all [[WorkerThreads]] are currently
   * parked, the fiber is placed on the external queue to increase fairness for compute-heavy
   * applications.
   *
   * If the request comes from a [[HelperTread]] or an external thread, the fiber is enqueued on
   * the external queue. Furthermore, if the request comes from an external thread, worker
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
      if (worker.isOwnedBy(pool) && !allWorkersBusy()) {
        worker.schedule(fiber)
      } else {
        scheduleExternal(fiber)
      }
    } else {
      scheduleExternal(fiber)
    }
  }

  /**
   * Determines whether all worker threads are currently unparked and not searching for work,
   * which may indicate synchronous, compute-heavy fiber usage.
   * @return
   *   true if all worker threads are currently unparked and not searching for work
   */
  private[this] def allWorkersBusy(): Boolean = {
    val st = state.get()
    (st & SearchMask) == 0 && ((st & UnparkMask) >>> UnparkShift) == threadCount
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
    externalQueue.offer(fiber, random)
    notifyParked(random)
    ()
  }

  /**
   * Returns a snapshot of the fibers currently live on this thread pool.
   *
   * @return
   *   a 3-tuple consisting of the set of fibers on the external queue, a map associating worker
   *   threads to the currently active fiber and fibers enqueued on the local queue of that
   *   worker thread and a set of suspended fibers tracked by this thread pool
   */
  private[unsafe] def liveFibers()
      : (Set[IOFiber[_]], Map[WorkerThread, (IOFiber[_], Set[IOFiber[_]])], Set[IOFiber[_]]) = {
    val externalFibers = externalQueue.snapshot().flatMap {
      case batch: Array[IOFiber[_]] => batch.toSet[IOFiber[_]]
      case fiber: IOFiber[_] => Set[IOFiber[_]](fiber)
      case _ => Set.empty[IOFiber[_]]
    }

    val map = mutable.Map.empty[WorkerThread, (IOFiber[_], Set[IOFiber[_]])]
    val suspended = mutable.Set.empty[IOFiber[_]]

    var i = 0
    while (i < threadCount) {
      val localFibers = localQueues(i).snapshot()
      val worker = workerThreads(i)
      val _ = parkedSignals(i).get()
      val active = worker.active
      map += (worker -> (active -> localFibers))
      suspended ++= worker.suspendedSnapshot()
      i += 1
    }

    (externalFibers, map.toMap, suspended.toSet)
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
      val fiber = runnable.asInstanceOf[IOFiber[_]]
      // Fast-path scheduling of a fiber without wrapping.
      scheduleFiber(fiber)
    } else {
      // Executing a general purpose computation on the thread pool.
      // Wrap the runnable in an `IO` and execute it as a fiber.
      val io = IO.delay(runnable.run())
      val fiber = new IOFiber[Unit](Map.empty, outcomeToUnit, io, this, self)
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
      workerThreadPublisher.get()

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

      // It is now safe to clean up the state of the thread pool.
      state.lazySet(0)

      // Shutdown and drain the external queue.
      externalQueue.clear()
      Thread.currentThread().interrupt()
    }
  }
}
