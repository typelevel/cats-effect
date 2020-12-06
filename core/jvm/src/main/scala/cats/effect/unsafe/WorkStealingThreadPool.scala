/*
 * Copyright 2020 Typelevel
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
 * This code is an adaptation of the `worker` and `idle` code from the `tokio` runtime.
 * The original source code in Rust is licensed under the MIT license and available
 * at: https://docs.rs/crate/tokio/0.2.22/source/src/runtime/thread_pool/worker.rs and
 * https://docs.rs/crate/tokio/0.2.22/source/src/runtime/thread_pool/idle.rs.
 *
 * For the reasoning behind the design decisions of this code, please consult:
 * https://tokio.rs/blog/2019-10-scheduler.
 */

package cats.effect
package unsafe

import scala.concurrent.ExecutionContext

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport

/**
 * Work-stealing thread pool which manages a pool of `WorkerThread`s for the specific purpose of executing `IOFiber`s
 * with work-stealing semantics.
 *
 * The thread pool starts with `threadCount` worker threads in the active state, looking to find fibers to
 * execute in their own local work stealing queues, or externally scheduled work coming from the external queue.
 *
 * In the case that a worker thread cannot find work to execute on its own queue, or the external queue,
 * it asks for permission from the pool to enter the searching state, which allows that thread to try
 * and steal work from the other worker threads. The pool tries to maintain that at most `threadCount / 2`
 * worker threads are searching for work, to reduce contention. Work stealing is tried linearly starting from
 * a random worker thread.
 */
private[effect] final class WorkStealingThreadPool(
    threadCount: Int, // number of worker threads
    threadPrefix: String, // prefix for the name of worker threads
    self0: => IORuntime
) extends WorkStealingThreadPool.StatePadding
    with ExecutionContext {

  import WorkStealingThreadPoolConstants._

  // Used to implement the `scala.concurrent.ExecutionContext` interface, for suspending
  // `java.lang.Runnable` instances into `IOFiber`s.
  private[this] lazy val self: IORuntime = self0

  // References to the worker threads.
  private[this] val workerThreads: Array[WorkerThread] = new Array(threadCount)

  // The external queue on which fibers coming from outside the pool are enqueued, or acts
  // as a place where spillover work from other local queues can go.
  private[this] val externalQueue: ConcurrentLinkedQueue[IOFiber[_]] =
    new ConcurrentLinkedQueue()

  // LIFO access to references of sleeping worker threads.
  private[this] val sleepers: ConcurrentCircularBuffer = new ConcurrentCircularBuffer(
    threadCount)

  // Shutdown signal for the worker threads.
  @volatile private[unsafe] var done: Boolean = false

  private[this] val stateOffset: Long = {
    try {
      val field = classOf[WorkStealingThreadPool.State].getDeclaredField("state")
      Unsafe.objectFieldOffset(field)
    } catch {
      case t: Throwable =>
        throw new ExceptionInInitializerError(t)
    }
  }

  // Initialization block.
  {
    state = threadCount << UnparkShift
    // Set up the worker threads.
    var i = 0
    while (i < threadCount) {
      val index = i
      val thread = new WorkerThread(index, this)
      thread.setName(s"$threadPrefix-$index")
      thread.setDaemon(true)
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
   * Tries to steal work from another worker thread. This method does a linear search of the
   * worker threads starting at a random index.
   */
  private[unsafe] def stealFromOtherWorkerThread(thread: WorkerThread): IOFiber[_] = {
    val from = thread.randomIndex(threadCount)
    var i = 0
    while (i < threadCount) {
      // Compute the index of the thread to steal from.
      val index = (from + i) % threadCount

      if (index != thread.getIndex()) {
        // Do not steal from yourself.
        val res = workerThreads(index).stealInto(thread.getQueue())
        if (res != null) {
          // Successful steal. Return the next fiber to be executed.
          return res
        }
      }

      i += 1
    }

    // The worker thread could not steal any work. Fall back to checking the external queue.
    externalDequeue()
  }

  /**
   * Checks the external queue for a fiber to execute next.
   */
  private[unsafe] def externalDequeue(): IOFiber[_] =
    externalQueue.poll()

  /**
   * Deregisters the current worker thread from the set of searching threads and asks for
   * help with the local work stealing queue.
   */
  private[unsafe] def transitionWorkerFromSearching(): Unit = {
    // Decrement the number of searching worker threads.
    val prev = Unsafe.getAndAddInt(this, stateOffset, -1)
    if (prev == 1) {
      // If this was the only searching thread, wake a thread up to potentially help out
      // with the local work queue.
      notifyParked()
    }
  }

  /**
   * Potentially unparks a worker thread.
   */
  private[unsafe] def notifyParked(): Unit = {
    // Find a worker thead to unpark.
    val worker = workerToNotify()
    LockSupport.unpark(worker)
  }

  /**
   * Searches for a parked thread to notify of arrived work.
   */
  private[this] def workerToNotify(): WorkerThread = {
    if (!notifyShouldWakeup()) {
      // Fast path, no locking, there are enough searching and/or running worker threads.
      // No need to wake up more. Return.
      return null
    }

    // Obtain the most recently parked thread.
    val worker = sleepers.dequeue()
    if (worker != null) {
      // Update the state so that a thread can be unparked.
      // Here we are updating the 16 most significant bits, which hold the
      // number of active threads.
      val delta = 1 | (1 << UnparkShift)
      Unsafe.getAndAddInt(this, stateOffset, delta)
      worker.sleeping = false
    }
    worker
  }

  /**
   * Checks the number of active and searching worker threads and decides whether
   * another thread should be notified of new work.
   *
   * Should wake up another worker thread when there are 0 searching threads and
   * fewer than `threadCount` active threads.
   */
  private[this] def notifyShouldWakeup(): Boolean = {
    val st = Unsafe.getInt(this, stateOffset)
    Unsafe.acquireFence()
    (st & SearchMask) == 0 && ((st & UnparkMask) >>> UnparkShift) < threadCount
  }

  /**
   * Updates the internal state to mark the given worker thread as parked.
   */
  private[unsafe] def transitionWorkerToParked(thread: WorkerThread): Boolean = {
    // Mark the thread as parked.
    thread.sleeping = true
    sleepers.enqueue(thread)
    // Decrement the number of unparked threads since we are parking.
    decrementNumberUnparked(thread.isSearching())
  }

  /**
   * Decrements the number of unparked worker threads. Potentially decrements
   * the number of searching threads if the parking thread was in the searching
   * state.
   *
   * Returns a `Boolean` value that represents whether this parking thread
   * was the last searching thread.
   */
  private[this] def decrementNumberUnparked(searching: Boolean): Boolean = {
    // Prepare for decrementing the 16 most significant bits that hold
    // the number of unparked threads.
    var dec = 1 << UnparkShift

    if (searching) {
      // Also decrement the 16 least significant bits that hold
      // the number of searching threads if the thread was in the searching state.
      dec += 1
    }

    // Atomically change the state.
    val prev = Unsafe.getAndAddInt(this, stateOffset, -dec)

    // Was this thread the last searching thread?
    searching && (prev & SearchMask) == 1
  }

  /**
   * Unparks a thread if there is pending work available.
   */
  private[unsafe] def notifyIfWorkPending(): Unit = {
    var i = 0
    while (i < threadCount) {
      // Check each worker thread for available work that can be stolen.
      if (!workerThreads(i).isEmpty()) {
        notifyParked()
        return
      }
      i += 1
    }

    if (!externalQueue.isEmpty()) {
      // If no work was found in the local queues of the worker threads, look for work in the external queue.
      notifyParked()
    }
  }

  /**
   * Decides whether a worker thread with no local or external work is allowed to enter the searching
   * state where it can look for work to steal from other worker threads.
   */
  private[unsafe] def transitionWorkerToSearching(): Boolean = {
    val st = Unsafe.getInt(this, stateOffset)
    Unsafe.acquireFence()

    // Try to keep at most around 50% threads that are searching for work, to reduce unnecessary contention.
    // It is not exactly 50%, but it is a good enough approximation.
    if (2 * (st & SearchMask) >= threadCount) {
      // There are enough searching worker threads. Do not allow this thread to enter the searching state.
      return false
    }

    // Allow this thread to enter the searching state.
    Unsafe.getAndAddInt(this, stateOffset, 1)
    true
  }

  /**
   * Tries rescheduling the fiber directly on the local work stealing queue, if executed from
   * a worker thread. Otherwise falls back to scheduling on the external queue.
   */
  private[effect] def executeFiber(fiber: IOFiber[_]): Unit = {
    if (Thread.currentThread().isInstanceOf[WorkerThread]) {
      rescheduleFiberAndNotify(fiber)
    } else {
      externalQueue.offer(fiber)
      notifyParked()
    }
  }

  /**
   * Reschedules the given fiber directly on the local work stealing queue on the same thread,
   * but with the possibility to skip notifying other fibers of a potential steal target, which
   * reduces contention in workloads running on fewer worker threads. This method executes an
   * unchecked cast to a `WorkerThread` and should only ever be called directly from a
   * `WorkerThread`.
   */
  private[effect] def rescheduleFiber(fiber: IOFiber[_]): Unit = {
    Thread.currentThread().asInstanceOf[WorkerThread].smartEnqueue(fiber, externalQueue)
  }

  /**
   * Reschedules the given fiber directly on the local work stealing queue on the same thread.
   * This method executes an unchecked cast to a `WorkerThread` and should only ever be called
   * directly from a `WorkerThread`.
   */
  private[effect] def rescheduleFiberAndNotify(fiber: IOFiber[_]): Unit = {
    Thread.currentThread().asInstanceOf[WorkerThread].enqueueAndNotify(fiber, externalQueue)
  }

  /**
   * Schedule a `java.lang.Runnable` for execution in this thread pool. The runnable
   * is suspended in an `IO` and executed as a fiber.
   */
  def execute(runnable: Runnable): Unit = {
    if (runnable.isInstanceOf[IOFiber[_]]) {
      executeFiber(runnable.asInstanceOf[IOFiber[_]])
    } else {
      // It's enough to only do this check here as there is no other way to submit work to the `ExecutionContext`
      // represented by this thread pool after it has been shutdown. Also, no one else can create raw fibers
      // directly, as `IOFiber` is not a public type.
      if (done) {
        return
      }

      // `unsafeRunFiber(true)` will enqueue the fiber, no need to do it manually
      IO(runnable.run()).unsafeRunFiber(reportFailure, _ => ())(self)
      ()
    }
  }

  def reportFailure(cause: Throwable): Unit = {
    cause.printStackTrace()
  }

  /**
   * Shutdown the thread pool. Calling this method after the pool has been shut down
   * has no effect.
   */
  def shutdown(): Unit = {
    if (done) {
      return
    }

    // Set the worker thread shutdown flag.
    done = true
    // Shutdown and drain the external queue.
    externalQueue.clear()
    // Send an interrupt signal to each of the worker threads.
    workerThreads.foreach(_.interrupt())
    // Remove the references to the worker threads so that they can be cleaned up, including their worker queues.
    for (i <- 0 until workerThreads.length) {
      workerThreads(i) = null
    }
  }
}

private[effect] object WorkStealingThreadPool {
  abstract class InitPadding extends Thread {
    protected var pinit00: Long = _
    protected var pinit01: Long = _
    protected var pinit02: Long = _
    protected var pinit03: Long = _
    protected var pinit04: Long = _
    protected var pinit05: Long = _
    protected var pinit06: Long = _
    protected var pinit07: Long = _
    protected var pinit08: Long = _
    protected var pinit09: Long = _
    protected var pinit10: Long = _
    protected var pinit11: Long = _
    protected var pinit12: Long = _
    protected var pinit13: Long = _
    protected var pinit14: Long = _
    protected var pinit15: Long = _
  }

  abstract class State extends InitPadding {
    // Represents two unsigned 16 bit integers.
    // The 16 most significant bits track the number of active (unparked) worker threads.
    // The 16 least significant bits track the number of worker threads that are searching
    // for work to steal from other worker threads.
    @volatile protected var state: Int = _
  }

  abstract class StatePadding extends State {
    protected var pstt00: Long = _
    protected var pstt01: Long = _
    protected var pstt02: Long = _
    protected var pstt03: Long = _
    protected var pstt04: Long = _
    protected var pstt05: Long = _
    protected var pstt06: Long = _
    protected var pstt07: Long = _
    protected var pstt08: Long = _
    protected var pstt09: Long = _
    protected var pstt10: Long = _
    protected var pstt11: Long = _
    protected var pstt12: Long = _
    protected var pstt13: Long = _
    protected var pstt14: Long = _
    protected var pstt15: Long = _
  }
}
