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
 * This code is an adaptation of the `worker` code from the `tokio` runtime.
 * The original source code in Rust is licensed under the MIT license and available
 * at: https://docs.rs/crate/tokio/0.2.22/source/src/runtime/thread_pool/worker.rs.
 *
 * For the reasoning behind the design decisions of this code, please consult:
 * https://tokio.rs/blog/2019-10-scheduler#the-next-generation-tokio-scheduler.
 */

package cats.effect
package unsafe

import scala.concurrent.{BlockContext, CanAwait}

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * Worker thread implementation used in `WorkStealingThreadPool`.
 * Each worker thread has its own `WorkStealingQueue` where
 * `IOFiber`s are scheduled for execution. This is the main
 * difference to a fixed thread executor, in which all threads
 * contend for work from a single shared queue.
 */
private final class WorkerThread(
    private[this] val index: Int, // index assigned by the thread pool in which this thread operates
    private[this] val threadCount: Int,
    private[this] val threadPrefix: String,
    private[this] val blockingThreadNameCounter: AtomicInteger,
    private[this] val workers: Array[WorkerThread],
    private[this] val external: ConcurrentLinkedQueue[IOFiber[_]],
    private[this] val stateOffset: Long,
    private[this] val pool: WorkStealingThreadPool // reference to the thread pool in which this thread operates
) extends WorkerThread.Padding
    with BlockContext {

  import WorkStealingThreadPoolConstants._
  import LocalQueueConstants._

  // Counter for periodically checking for any fibers coming from the external queue.
  private[this] var ticks: Int = 0

  // Flag that indicates that this worker thread is actively searching for work and
  // trying to steal some from the other threads.
  private[this] var searching: Boolean = false

  // Source of randomness.
  private[this] var random: ThreadLocalRandom = _

  private[this] var bypass: IOFiber[_] = null

  private[this] var blocked: Boolean = false

  private[this] val drainList: FiberArrayList = new FiberArrayList(LocalQueueCapacityNumber)

  private[this] val sleepingOffset: Long = {
    try {
      val field = classOf[WorkerThread.Sleeping].getDeclaredField("sleeping")
      Unsafe.objectFieldOffset(field)
    } catch {
      case t: Throwable =>
        throw new ExceptionInInitializerError(t)
    }
  }

  def isSleeping(): Boolean = {
    Unsafe.getIntVolatile(this, sleepingOffset) != 0
  }

  def tryWakeUp(): Boolean = {
    Unsafe.compareAndSwapInt(this, sleepingOffset, 1, 0)
  }

  /**
   * Enqueues a fiber to the local work stealing queue. This method always
   * notifies another thread that a steal should be attempted from this queue.
   */
  def schedule(fiber: IOFiber[_]): Unit = {
    enqueue(fiber, external)
    notifyParked()
  }

  /**
   * Enqueues a fiber to the local work stealing queue. This method can skip
   * notifying another thread about potential work to be stolen if it can be
   * determined that this is a mostly single fiber workload.
   */
  def reschedule(fiber: IOFiber[_]): Unit = {
    if (isEmpty()) {
      bypass = fiber
    } else {
      enqueue(fiber, external)
      notifyParked()
    }
  }

  /**
   * Obtain a fiber either from the local queue or the external queue,
   * if the time has come to check for work from there. Returns `null`
   * when no fiber is available.
   */
  private[this] def nextFiber(): IOFiber[_] = {
    if (bypass != null) {
      val f = bypass
      bypass = null
      ticks -= 1
      f
    } else {
      if ((ticks & ExternalCheckIterationsMask) == 0) {
        val f = external.poll()
        if (f == null) dequeue() else f
      } else {
        val f = dequeue()
        if (f == null) external.poll() else f
      }
    }
  }

  /**
   * Execute the found fiber.
   */
  private[this] def runFiber(fiber: IOFiber[_]): Unit = {
    // Announce that this thread is no longer searching for work, if that was the case before.
    transitionFromSearching()
    // Execute the fiber.
    fiber.run()
  }

  /**
   * Update the pool state that tracks searching threads, as this thread is no longer searching.
   */
  private[this] def transitionFromSearching(): Unit = {
    if (!searching) {
      // This thread wasn't searching for work. Nothing to do.
      return
    }
    // Update the local state.
    searching = false
    // Update the global state.
    // Decrement the number of searching worker threads.
    val prev = Unsafe.getAndAddInt(pool, stateOffset, -1)
    if (prev == 1) {
      // If this was the only searching thread, wake a thread up to potentially help out
      // with the local work queue.
      notifyParked()
    }
  }

  /**
   * Potentially unparks a worker thread.
   */
  private[this] def notifyParked(): Unit = {
    val st = Unsafe.getIntVolatile(pool, stateOffset)
    if ((st & SearchMask) == 0 && ((st & UnparkMask) >>> UnparkShift) < threadCount) {
      val from = randomIndex()
      var i = 0
      while (i < threadCount) {
        val idx = (from + i) % threadCount
        val worker = workers(idx)
        if (Unsafe.getIntVolatile(worker, sleepingOffset) != 0
          && Unsafe.compareAndSwapInt(worker, sleepingOffset, 1, 0)) {
          Unsafe.getAndAddInt(pool, stateOffset, (1 << UnparkShift) | 1)
          LockSupport.unpark(worker)
          return
        }
        i += 1
      }
    }
  }

  /**
   * Park this thread as there is no available work for it.
   */
  private[this] def park(): Unit = {
    // Update the global pool state that tracks the status of worker threads.
    transitionToParked()

    // Only actually park if the pool has not been shut down. Otherwise, this
    // will break out of the run loop and end the thread.
    while (!pool.done) {
      // Park the thread until further notice.
      LockSupport.park(pool)

      // Spurious wakeup check.
      if (transitionFromParked()) {
        if (nonEmpty()) {
          // The local queue can be potentially stolen from. Notify a worker thread.
          notifyParked()
        }
        // The thread has been notified to unpark.
        // Break out of the parking loop.
        return
      }

      // Spurious wakeup. Go back to sleep.
    }
  }

  private[this] def transitionToParked(): Unit = {
    // Mark the thread as parked.
    Unsafe.putOrderedInt(this, sleepingOffset, 1)

    // Decrement the number of unparked threads since we are parking.
    // Prepare for decrementing the 16 most significant bits that hold
    // the number of unparked threads.
    var dec = 1 << UnparkShift

    if (searching) {
      // Also decrement the 16 least significant bits that hold
      // the number of searching threads if the thread was in the searching state.
      dec += 1
    }

    // Atomically change the state.
    val prev = Unsafe.getAndAddInt(pool, stateOffset, -dec)

    // Was this thread the last searching thread?
    val isLastSearcher = searching && (prev & SearchMask) == 1
    searching = false
    if (isLastSearcher) {
      var i = 0
      while (i < threadCount) {
        // Check each worker thread for available work that can be stolen.
        if (workers(i).nonEmpty()) {
          notifyParked()
          return
        }
        i += 1
      }

      if (!external.isEmpty()) {
        // If no work was found in the local queues of the worker threads, look for work in the external queue.
        notifyParked()
      }
    }
  }

  /**
   * Guard against spurious wakeups. Check the global pool state to distinguish
   * between an actual wakeup notification and an unplanned wakeup.
   */
  private[this] def transitionFromParked(): Boolean = {
    if (Unsafe.getIntVolatile(this, sleepingOffset) != 0) {
      // Should remain parked.
      false
    } else {
      // Actual notification. When unparked, a worker thread goes directly into
      // the searching state.
      searching = true
      // Unsafe.getAndAddInt(pool, stateOffset, (1 << UnparkShift) | 1)
      true
    }
  }

  /**
   * Try to steal work from other worker threads.
   */
  private[this] def stealWork(): IOFiber[_] = {
    // Announce the intent to steal work from other threads.
    if (!transitionToSearching()) {
      // It has been decided that this thread should not try
      // to steal work from other worker threads. It should
      // be parked instead.
      return null
    }

    // This thread has been allowed to steal work from other worker threads.
    val from = randomIndex()
    var i = 0
    while (i < threadCount) {
      // Compute the index of the thread to steal from.
      val idx = (from + i) % threadCount

      if (idx != index) {
        // Do not steal from yourself.
        val res = workers(idx).steal(this)
        if (res != null) {
          // Successful steal. Return the next fiber to be executed.
          return res
        }
      }

      i += 1
    }

    // The worker thread could not steal any work. Fall back to checking the external queue.
    external.poll()
  }

  /**
   * Ask the pool for permission to steal work from other worker threads.
   */
  private[this] def transitionToSearching(): Boolean = {
    if (!searching) {
      // If this thread is not currently searching for work, ask the pool for permission.
      val st = Unsafe.getIntVolatile(pool, stateOffset)

      // Try to keep at most around 50% threads that are searching for work, to reduce unnecessary contention.
      // It is not exactly 50%, but it is a good enough approximation.
      if (2 * (st & SearchMask) >= threadCount) {
        // There are enough searching worker threads. Do not allow this thread to enter the searching state.
        searching = false
      } else {
        // Allow this thread to enter the searching state.
        Unsafe.getAndAddInt(pool, stateOffset, 1)
        searching = true
      }
    }
    // Return the decision by the pool.
    searching
  }

  /**
   * Generates a random worker thread index.
   */
  private[this] def randomIndex(): Int =
    random.nextInt(threadCount)

  /**
   * The main run loop of this worker thread.
   */
  override def run(): Unit = {
    random = ThreadLocalRandom.current()
    // A mutable reference to the next fiber to be executed.
    // Do not forget to null out at the end of each iteration.
    var fiber: IOFiber[_] = null

    // Loop until the pool has been shutdown.
    while (!pool.done) {
      // Try to obtain a fiber from the local queue or the external
      // queue, depending on the number of passed iterations.
      fiber = nextFiber()

      if (fiber == null) {
        // No available fibers in the local or the external queue.
        // Try to steal work from other worker threads.
        fiber = stealWork()
      }

      if (fiber == null) {
        // No fiber has been stolen from any other worker thread.
        // There is probably not enough work for every thread in
        // the pool. It's time to park and await a notification
        // when new work is submitted to the pool.
        park()
        fiber = stealWork()
      }

      if (fiber != null) {
        // There is a fiber that can be executed, so do it.
        runFiber(fiber)
        // Do not forget to null out the reference, so the fiber
        // can be garbage collected.
        fiber = null
      }

      ticks += 1 // Count each iteration.
    }
  }

  override def blockOn[T](thunk: => T)(implicit permission: CanAwait): T = {
    drain(drainList)
    external.addAll(drainList)
    drainList.reset()
    if (blocked) {
      thunk
    } else {
      blocked = true
      val helper = new BlockingThread(threadPrefix, blockingThreadNameCounter, external, pool)
      helper.setName(s"$threadPrefix-blocking-${blockingThreadNameCounter.incrementAndGet()}")
      helper.setDaemon(true)
      helper.start()
      val result = thunk
      helper.setSignal(1)
      helper.join()
      blocked = false
      result
    }
  }
}

private object WorkerThread {
  abstract class InitPadding extends LocalQueue {
    protected val pinit00: Long = 0
    protected val pinit01: Long = 0
    protected val pinit02: Long = 0
    protected val pinit03: Long = 0
    protected val pinit04: Long = 0
    protected val pinit05: Long = 0
    protected val pinit06: Long = 0
    protected val pinit07: Long = 0
    protected val pinit08: Long = 0
    protected val pinit09: Long = 0
    protected val pinit10: Long = 0
    protected val pinit11: Long = 0
    protected val pinit12: Long = 0
    protected val pinit13: Long = 0
    protected val pinit14: Long = 0
    protected val pinit15: Long = 0
  }

  abstract class Sleeping extends InitPadding {
    @volatile protected var sleeping: Int = 0
  }

  abstract class Padding extends Sleeping {
    protected val pwkth00: Long = 0
    protected val pwkth01: Long = 0
    protected val pwkth02: Long = 0
    protected val pwkth03: Long = 0
    protected val pwkth04: Long = 0
    protected val pwkth05: Long = 0
    protected val pwkth06: Long = 0
    protected val pwkth07: Long = 0
    protected val pwkth08: Long = 0
    protected val pwkth09: Long = 0
    protected val pwkth10: Long = 0
    protected val pwkth11: Long = 0
    protected val pwkth12: Long = 0
    protected val pwkth13: Long = 0
    protected val pwkth14: Long = 0
    protected val pwkth15: Long = 0
  }
}
