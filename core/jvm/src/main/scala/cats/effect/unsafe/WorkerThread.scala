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
 * This code is an adaptation of the `worker` code from the `tokio` runtime.
 * The original source code in Rust is licensed under the MIT license and available
 * at: https://docs.rs/crate/tokio/0.2.22/source/src/runtime/thread_pool/worker.rs.
 *
 * For the reasoning behind the design decisions of this code, please consult:
 * https://tokio.rs/blog/2019-10-scheduler#the-next-generation-tokio-scheduler.
 */

package cats.effect
package unsafe

import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
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
    private[this] val pool: WorkStealingThreadPool // reference to the thread pool in which this thread operates
) extends Thread {

  import WorkStealingThreadPoolConstants._

  // The local work stealing queue where tasks are scheduled without contention.
  private[this] val queue: WorkStealingQueue = new WorkStealingQueue()

  // Counter for periodically checking for any fibers coming from the external queue.
  private[this] var tick: Int = 0

  // Flag that indicates that this worker thread is actively searching for work and
  // trying to steal some from the other threads.
  private[this] var searching: Boolean = false

  // Source of randomness.
  private[this] val random: Random = new Random()

  // Flag that indicates that this worker thread is currently sleeping, in order to
  // guard against spurious wakeups.
  @volatile private[unsafe] var sleeping: Boolean = false

  /**
   * Enqueues a fiber to the local work stealing queue. This method always
   * notifies another thread that a steal should be attempted from this queue.
   */
  def enqueueAndNotify(fiber: IOFiber[_], external: ConcurrentLinkedQueue[IOFiber[_]]): Unit = {
    queue.enqueue(fiber, external)
    pool.notifyParked()
  }

  /**
   * Enqueues a fiber to the local work stealing queue. This method can skip
   * notifying another thread about potential work to be stolen if it can be
   * determined that this is a mostly single fiber workload.
   */
  def smartEnqueue(fiber: IOFiber[_], external: ConcurrentLinkedQueue[IOFiber[_]]): Unit = {
    // Check if the local queue is empty **before** enqueueing the given fiber.
    val empty = queue.isEmpty()
    queue.enqueue(fiber, external)
    if (tick == ExternalCheckIterationsMask || !empty) {
      // On the next iteration, this worker thread will check for new work from
      // the external queue, which means that another thread should be woken up
      // to contend for the enqueued fiber.
      // It could also be the case that the current queue was not empty before
      // the fiber was enqueued, in which case another thread should wake up
      // to help out.
      pool.notifyParked()
    }
  }

  /**
   * A forwarder method for stealing work from the local work stealing queue in
   * this thread into the `into` queue that belongs to another thread.
   */
  def stealInto(into: WorkStealingQueue): IOFiber[_] =
    queue.stealInto(into)

  /**
   * A forwarder method for checking if the local work stealing queue contains
   * any fibers.
   */
  def isEmpty(): Boolean =
    queue.isEmpty()

  /**
   * Returns true if this worker thread is actively searching for work and
   * looking to steal some from other worker threads.
   */
  def isSearching(): Boolean =
    searching

  /**
   * Returns the work stealing thread pool index of this worker thread.
   */
  def getIndex(): Int =
    index

  /**
   * Returns the local work stealing queue of this worker thread.
   */
  def getQueue(): WorkStealingQueue =
    queue

  /**
   * Obtain a fiber either from the local queue or the external queue,
   * if the time has come to check for work from there. Returns `null`
   * when no fiber is available.
   */
  private[this] def nextFiber(): IOFiber[_] = {
    var fiber: IOFiber[_] = null

    // Decide whether it's time to look for work in the external queue.
    if ((tick & ExternalCheckIterationsMask) == 0) {
      // It is time to check the external queue.
      fiber = pool.externalDequeue()
      if (fiber == null) {
        // Fall back to checking the local queue.
        fiber = queue.dequeueLocally()
      }
    } else {
      // Normal case, look for work in the local queue.
      fiber = queue.dequeueLocally()
      if (fiber == null) {
        // Fall back to checking the external queue.
        fiber = pool.externalDequeue()
      }
    }

    // Return the fiber if any has been found.
    fiber
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
    pool.transitionWorkerFromSearching()
  }

  /**
   * Park this thread as there is no available work for it.
   */
  private[this] def park(): Unit = {
    // Update the global pool state that tracks the status of worker threads.
    transitionToParked()

    // Only actually park if the pool has not been shut down. Otherwise, this
    // will break out of the run loop and end the thread.
    while (!pool.done && !isInterrupted()) {
      // Park the thread until further notice.
      LockSupport.park(pool)

      // Spurious wakeup check.
      if (transitionFromParked()) {
        if (queue.isStealable()) {
          // The local queue can be potentially stolen from. Notify a worker thread.
          pool.notifyParked()
        }
        // The thread has been notified to unpark.
        // Break out of the parking loop.
        return
      }

      // Spurious wakeup. Go back to sleep.
    }
  }

  private[this] def transitionToParked(): Unit = {
    val isLastSearcher = pool.transitionWorkerToParked(this)
    searching = false
    if (isLastSearcher) {
      pool.notifyIfWorkPending()
    }
  }

  /**
   * Guard against spurious wakeups. Check the global pool state to distinguish
   * between an actual wakeup notification and an unplanned wakeup.
   */
  private[this] def transitionFromParked(): Boolean = {
    if (sleeping) {
      // Should remain parked.
      false
    } else {
      // Actual notification. When unparked, a worker thread goes directly into
      // the searching state.
      searching = true
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
    pool.stealFromOtherWorkerThread(this)
  }

  /**
   * Ask the pool for permission to steal work from other worker threads.
   */
  private[this] def transitionToSearching(): Boolean = {
    if (!searching) {
      // If this thread is not currently searching for work, ask the pool for permission.
      searching = pool.transitionWorkerToSearching()
    }
    // Return the decision by the pool.
    searching
  }

  /**
   * Generates a random worker thread index.
   */
  private[unsafe] def randomIndex(bound: Int): Int =
    random.nextInt(bound)

  /**
   * The main run loop of this worker thread.
   */
  override def run(): Unit = {
    // A mutable reference to the next fiber to be executed.
    // Do not forget to null out at the end of each iteration.
    var fiber: IOFiber[_] = null

    // Loop until the pool has been shutdown.
    while (!pool.done && !isInterrupted()) {
      tick += 1 // Count each iteration.

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
      } else {
        // There is a fiber that can be executed, so do it.
        runFiber(fiber)
        // Do not forget to null out the reference, so the fiber
        // can be garbage collected.
        fiber = null
      }
    }
  }
}
