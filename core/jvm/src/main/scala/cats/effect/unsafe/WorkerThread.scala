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

import scala.annotation.switch
import scala.concurrent.{BlockContext, CanAwait}

import java.util.ArrayList
import java.util.concurrent.{ConcurrentLinkedQueue, ThreadLocalRandom}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.locks.LockSupport

/**
 * Implementation of the worker thread at the heart of the
 * [[WorkStealingThreadPool]].
 *
 * Each worker thread is assigned exclusive write access to a single
 * [[LocalQueue]] instance of [[IOFiber]] references which other worker threads
 * can steal when they run out of work in their local queue.
 *
 * The existence of multiple work queues dramatically reduces contention in a
 * highly parallel system when compared to a fixed size thread pool whose worker
 * threads all draw tasks from a single global work queue.
 */
private[effect] final class WorkerThread(
    // Index assigned by the `WorkStealingThreadPool` for identification purposes.
    private[unsafe] val index: Int,
    // The size of the `WorkStealingThreadPool`. Used when generating random searching indices.
    private[this] val threadCount: Int,
    // Thread prefix string used for naming new instances of `WorkerThread` and `HelperThread`.
    private[this] val threadPrefix: String,
    // Instance to a global counter used when naming new instances of `HelperThread`.
    private[this] val blockingThreadCounter: AtomicInteger,
    // Local queue instance with exclusive write access.
    private[this] val queue: LocalQueue,
    // The state of the `WorkerThread` (parked/unparked).
    private[this] val parked: AtomicBoolean,
    // Overflow queue used by the local queue for offloading excess fibers, as well as
    // for drawing fibers when the local queue is exhausted.
    private[this] val overflow: ConcurrentLinkedQueue[IOFiber[_]],
    // Reference to the `WorkStealingThreadPool` in which this thread operates.
    private[this] val pool: WorkStealingThreadPool)
    extends Thread
    with BlockContext {

  import LocalQueueConstants._
  import WorkStealingThreadPoolConstants._

  /**
   * An array backed list for purposes of draining the local queue in
   * anticipation of execution of blocking code.
   */
  private[this] val drain: ArrayList[IOFiber[_]] = new ArrayList(LocalQueueCapacity)

  /**
   * A flag which is set whenever a blocking code region is entered. This is
   * useful for detecting nested blocking regions, in order to avoid
   * unnecessarily spawning extra [[HelperThread]]s.
   */
  private[this] var blocking: Boolean = false

  // Constructor code.
  {
    // Worker threads are daemon threads.
    setDaemon(true)

    // Set the name of this thread.
    setName(s"$threadPrefix-$index")
  }

  /**
   * Enqueues a fiber to the local work stealing queue. This method always
   * notifies another thread that a steal should be attempted from this queue.
   */
  def schedule(fiber: IOFiber[_]): Unit = {
    queue.enqueue(fiber, overflow)
    pool.notifyParked()
  }

  /**
   * Enqueues a fiber to the local work stealing queue. This method can skip
   * notifying another thread about potential work to be stolen if it can be
   * determined that this is a mostly single fiber workload.
   */
  def reschedule(fiber: IOFiber[_]): Unit = {
    schedule(fiber)
  }

  override def run(): Unit = {

    /*
     * Uncontented source of randomness. By default, `java.util.Random` is
     * thread safe, which is a feature we do not need in this class, as the
     * source of randomness is completely isolated to each instance of
     * `WorkerThread`. The instance is obtained only once at the beginning of
     * this method, to avoid the cost of the `ThreadLocal` mechanism at runtime.
     */
    val random = ThreadLocalRandom.current()

    var searching = false

    /*
     * A counter (modulo `OverflowQueueTicks`) which represents the
     * `WorkerThread` finite state machine. The following values have special
     * semantics explained here:
     *
     *   0: To increase the fairness towards fibers scheduled by threads which
     *      are external to the `WorkStealingThreadPool`, every
     *      `OverflowQueueTicks` number of iterations, the overflow queue takes
     *      precedence over the local queue.
     *
     *      If a fiber is successfully dequeued from the overflow queue, it will
     *      be executed. Regardless of this outcome, the `WorkerThread`
     *      transitions to executing fibers from the local queue
     *      (state value 3 and upwards).
     *
     *      This state occurs "naturally" after a certain number of executions
     *      from the local queue (when the state value wraps around modulo
     *      `OverflowQueueTicks`).
     *
     *   1: Fall back to checking the overflow queue after a failed dequeue from
     *      the local queue. Depending on the outcome of this check, the
     *      `WorkerThread` transitions to executing fibers from the local queue
     *      in the case of a successful dequeue from the overflow queue.
     *      Otherwise, the `WorkerThread` transitions to stealing fibers from
     *      other `WorkerThread`s (state value 2).
     *
     *   2: Proceed to steal fibers from other `WorkerThread`s. Depending on the
     *      outcome of the stealing attempt, the `WorkerThread` transitions to
     *      executing fibers from the local queue in the case of a successful
     *      steal (state value 3 and upwards). Otherwise, the `WorkerThread`
     *      parks until notified of new work. After the `WorkerThread` has been
     *      unparked, it transitions to checking the overflow queue
     *      (state value 1).
     *
     *   3 and upwards: Look for fibers to execute in the local queue. In case
     *      of a successful dequeue from the local queue, increment the state
     *      value. In case of a failed dequeue from the local queue, transition
     *      to state value 1.
     */
    var state = 0

    while (!isInterrupted()) {
      ((state & OverflowQueueTicksMask): @switch) match {
        case 0 =>
          val fiber = overflow.poll()
          if (fiber ne null) {
            // Run the fiber.
            fiber.run()
          }
          // Transition to executing fibers from the local queue.
          state = 5

        case 1 =>
          val fiber = overflow.poll()
          if (fiber ne null) {
            // Run the fiber.
            fiber.run()
            // Transition to executing fibers from the local queue.
            state = 5
          } else {
            // Transition to stealing fibers from other `WorkerThread`s.
            state = 2
          }

        case 2 =>
          // Ask the pool for permission to steal from other worker threads.
          if (!searching) {
            searching = pool.transitionWorkerToSearching()
          }
          if (!searching) {
            // Permission to steal not granted. Park.
            state = 3
          } else {
            // This thread has been allowed to steal work from other workers.
            val fiber = pool.stealFromOtherWorkerThread(index, random.nextInt(threadCount))
            if (fiber ne null) {
              // Run the stolen fiber.
              searching = false
              pool.transitionWorkerFromSearching()
              fiber.run()
              // Transition to executing fibers from the local queue.
              state = 5
            } else {
              // Stealing attempt is unsuccessful. Park.
              state = 3
            }
          }

        case 3 =>
          parked.lazySet(true)
          val isLastSearcher = pool.transitionWorkerToParked(this, searching)
          searching = false
          if (isLastSearcher) {
            pool.notifyIfWorkPending()
          }

          var cont = true
          while (cont && !isInterrupted()) {
            // Park the thread until further notice.
            LockSupport.park(pool)

            // Spurious wakeup check.
            cont = parked.get()
          }

          searching = true
          state = 4

        case 4 =>
          val fiber = overflow.poll()
          if (fiber ne null) {
            if (searching) {
              searching = false
              pool.transitionWorkerFromSearching()
            }
            // Run the fiber.
            fiber.run()
            // Transition to executing fibers from the local queue.
            state = 5
          } else {
            // Transition to stealing fibers from other `WorkerThread`s.
            state = 2
          }

        case _ =>
          // Dequeue a fiber from the local queue.
          val fiber = queue.dequeue()
          if (fiber ne null) {
            // Run the stolen fiber.
            fiber.run()
            // Continue executing fibers from the local queue.
            state += 1
          } else {
            // Transition to checking the overflow queue.
            state = 1
          }
      }
    }
  }

  /**
   * A mechanism for executing support code before executing a blocking action.
   *
   * This is a slightly more involved implementation of the support code in
   * anticipation of running blocking code, also implemented in [[WorkerThread]].
   *
   * For a more detailed discussion on the design principles behind the support
   * for running blocking actions on the [[WorkStealingThreadPool]], check the
   * code comments for [[HelperThread]].
   *
   * The main difference between this and the implementation in [[HelperThread]]
   * is that [[WorkerThread]]s need to take care of draining their
   * [[LocalQueue]] to the `overflow` queue before entering the blocking region.
   *
   * The reason why this code is duplicated, instead of inherited is to keep the
   * monomorphic callsites in the `IOFiber` runloop.
   */
  override def blockOn[T](thunk: => T)(implicit permission: CanAwait): T = {
    // Drain the local queue to the `overflow` queue.
    queue.drain(drain)
    overflow.addAll(drain)
    drain.clear()

    if (blocking) {
      // This `WorkerThread` is already inside an enclosing blocking region.
      // There is no need to spawn another `HelperThread`. Instead, directly
      // execute the blocking action.
      thunk
    } else {
      // Spawn a new `HelperThread` to take the place of this thread, as the
      // current thread prepares to execute a blocking action.

      // Logically enter the blocking region.
      blocking = true

      // Spawn a new `HelperThread`.
      val helper = new HelperThread(threadPrefix, blockingThreadCounter, overflow, pool)
      helper.setName(
        s"$threadPrefix-blocking-helper-${blockingThreadCounter.incrementAndGet()}")
      helper.setDaemon(true)
      helper.start()

      // With another `HelperThread` started, it is time to execute the blocking
      // action.
      val result = thunk

      // Blocking is finished. Time to signal the spawned helper thread.
      helper.setSignal()

      // Do not proceed until the helper thread has fully died. This is terrible
      // for performance, but it is justified in this case as the stability of
      // the `WorkStealingThreadPool` is of utmost importance in the face of
      // blocking, which in itself is **not** what the pool is optimized for.
      // In practice however, unless looking at a completely pathological case
      // of propagating blocking actions on every spawned helper thread, this is
      // not an issue, as the `HelperThread`s are all executing `IOFiber[_]`
      // instances, which mostly consist of non-blocking code.
      helper.join()

      // Logically exit the blocking region.
      blocking = false

      // Return the computed result from the blocking operation
      result
    }
  }
}

// /**
//  * Worker thread implementation used in `WorkStealingThreadPool`.
//  * Each worker thread has its own `LocalQueue` where
//  * `IOFiber`s are scheduled for execution. This is the main
//  * difference to a fixed thread executor, in which all threads
//  * contend for work from a single shared queue.
//  */
// private[effect] final class OldWorkerThread(
//     private[this] val index: Int, // index assigned by the thread pool in which this thread operates
//     private[this] val threadPrefix: String,
//     private[this] val blockingThreadCounter: AtomicInteger,
//     private[this] val overflow: ConcurrentLinkedQueue[IOFiber[_]],
//     private[this] val pool: WorkStealingThreadPool // reference to the thread pool in which this thread operates
// ) extends Thread
//     with BlockContext {

//   import WorkStealingThreadPoolConstants._
//   import LocalQueueConstants._

//   // The local work stealing queue where tasks are scheduled without contention.
//   private[this] val queue: LocalQueue = new LocalQueue()

//   // Counter for periodically checking for any fibers coming from the external queue.
//   private[this] var tick: Int = 0

//   // Flag that indicates that this worker thread is actively searching for work and
//   // trying to steal some from the other threads.
//   private[this] var searching: Boolean = false

//   // Source of randomness.
//   private[this] val random: Random = new Random()

//   // Flag that indicates that this worker thread is currently sleeping, in order to
//   // guard against spurious wakeups.
//   @volatile private[unsafe] var sleeping: Boolean = false

//   /**
//    * An array backed list for purposes of draining the local queue in
//    * anticipation of execution of blocking code.
//    */
//   private[this] val drain: ArrayList[IOFiber[_]] = new ArrayList(LocalQueueCapacity)

//   /**
//    * A flag which is set whenever a blocking code region is entered. This is
//    * useful for detecting nested blocking regions, in order to avoid
//    * unnecessarily spawning extra [[HelperThread]]s.
//    */
//   private[this] var blocking: Boolean = false

//   /**
//    * Enqueues a fiber to the local work stealing queue. This method always
//    * notifies another thread that a steal should be attempted from this queue.
//    */
//   def schedule(fiber: IOFiber[_]): Unit = {
//     queue.enqueue(fiber, overflow)
//     pool.notifyParked()
//   }

//   /**
//    * Enqueues a fiber to the local work stealing queue. This method can skip
//    * notifying another thread about potential work to be stolen if it can be
//    * determined that this is a mostly single fiber workload.
//    */
//   def reschedule(fiber: IOFiber[_]): Unit = {
//     // Check if the local queue is empty **before** enqueueing the given fiber.
//     val empty = queue.isEmpty()
//     queue.enqueue(fiber, overflow)
//     if (tick == ExternalCheckIterationsMask || !empty) {
//       // On the next iteration, this worker thread will check for new work from
//       // the external queue, which means that another thread should be woken up
//       // to contend for the enqueued fiber.
//       // It could also be the case that the current queue was not empty before
//       // the fiber was enqueued, in which case another thread should wake up
//       // to help out.
//       pool.notifyParked()
//     }
//   }

//   /**
//    * A forwarder method for stealing work from the local work stealing queue in
//    * this thread into the `into` queue that belongs to another thread.
//    */
//   private[unsafe] def stealInto(into: LocalQueue): IOFiber[_] =
//     queue.stealInto(into)

//   /**
//    * A forwarder method for checking if the local work stealing queue contains
//    * any fibers.
//    */
//   private[unsafe] def isEmpty(): Boolean =
//     queue.isEmpty()

//   /**
//    * Returns true if this worker thread is actively searching for work and
//    * looking to steal some from other worker threads.
//    */
//   private[unsafe] def isSearching(): Boolean =
//     searching

//   /**
//    * Returns the work stealing thread pool index of this worker thread.
//    */
//   private[unsafe] def getIndex(): Int =
//     index

//   /**
//    * Returns the local work stealing queue of this worker thread.
//    */
//   private[unsafe] def getQueue(): LocalQueue =
//     queue

//   /**
//    * Obtain a fiber either from the local queue or the external queue,
//    * if the time has come to check for work from there. Returns `null`
//    * when no fiber is available.
//    */
//   private[this] def nextFiber(): IOFiber[_] = {
//     var fiber: IOFiber[_] = null

//     // Decide whether it's time to look for work in the external queue.
//     if ((tick & ExternalCheckIterationsMask) == 0) {
//       // It is time to check the external queue.
//       fiber = overflow.poll()
//       if (fiber == null) {
//         // Fall back to checking the local queue.
//         fiber = queue.dequeue()
//       }
//     } else {
//       // Normal case, look for work in the local queue.
//       fiber = queue.dequeue()
//       if (fiber == null) {
//         // Fall back to checking the external queue.
//         fiber = overflow.poll()
//       }
//     }

//     // Return the fiber if any has been found.
//     fiber
//   }

//   /**
//    * Execute the found fiber.
//    */
//   private[this] def runFiber(fiber: IOFiber[_]): Unit = {
//     // Announce that this thread is no longer searching for work, if that was the case before.
//     transitionFromSearching()
//     // Execute the fiber.
//     fiber.run()
//   }

//   /**
//    * Update the pool state that tracks searching threads, as this thread is no longer searching.
//    */
//   private[this] def transitionFromSearching(): Unit = {
//     if (!searching) {
//       // This thread wasn't searching for work. Nothing to do.
//       return
//     }
//     // Update the local state.
//     searching = false
//     // Update the global state.
//     pool.transitionWorkerFromSearching()
//   }

//   /**
//    * Park this thread as there is no available work for it.
//    */
//   private[this] def park(): Unit = {
//     // Update the global pool state that tracks the status of worker threads.
//     transitionToParked()

//     // Only actually park if the pool has not been shut down. Otherwise, this
//     // will break out of the run loop and end the thread.
//     while (!pool.done && !isInterrupted()) {
//       // Park the thread until further notice.
//       LockSupport.park(pool)

//       // Spurious wakeup check.
//       if (transitionFromParked()) {
//         if (queue.nonEmpty()) {
//           // The local queue can be potentially stolen from. Notify a worker thread.
//           pool.notifyParked()
//         }
//         // The thread has been notified to unpark.
//         // Break out of the parking loop.
//         return
//       }

//       // Spurious wakeup. Go back to sleep.
//     }
//   }

//   private[this] def transitionToParked(): Unit = {
//     val isLastSearcher = pool.transitionWorkerToParked(this)
//     searching = false
//     if (isLastSearcher) {
//       pool.notifyIfWorkPending()
//     }
//   }

//   /**
//    * Guard against spurious wakeups. Check the global pool state to distinguish
//    * between an actual wakeup notification and an unplanned wakeup.
//    */
//   private[this] def transitionFromParked(): Boolean = {
//     if (sleeping) {
//       // Should remain parked.
//       false
//     } else {
//       // Actual notification. When unparked, a worker thread goes directly into
//       // the searching state.
//       searching = true
//       true
//     }
//   }

//   /**
//    * Try to steal work from other worker threads.
//    */
//   private[this] def stealWork(): IOFiber[_] = {
//     // Announce the intent to steal work from other threads.
//     if (!transitionToSearching()) {
//       // It has been decided that this thread should not try
//       // to steal work from other worker threads. It should
//       // be parked instead.
//       return null
//     }

//     // This thread has been allowed to steal work from other worker threads.
//     pool.stealFromOtherWorkerThread(this)
//   }

//   /**
//    * Ask the pool for permission to steal work from other worker threads.
//    */
//   private[this] def transitionToSearching(): Boolean = {
//     if (!searching) {
//       // If this thread is not currently searching for work, ask the pool for permission.
//       searching = pool.transitionWorkerToSearching()
//     }
//     // Return the decision by the pool.
//     searching
//   }

//   /**
//    * Generates a random worker thread index.
//    */
//   private[unsafe] def randomIndex(bound: Int): Int =
//     random.nextInt(bound)

//   /**
//    * The main run loop of this worker thread.
//    */
//   override def run(): Unit = {
//     // A mutable reference to the next fiber to be executed.
//     // Do not forget to null out at the end of each iteration.
//     var fiber: IOFiber[_] = null

//     // Loop until the pool has been shutdown.
//     while (!pool.done && !isInterrupted()) {
//       tick += 1 // Count each iteration.

//       // Try to obtain a fiber from the local queue or the external
//       // queue, depending on the number of passed iterations.
//       fiber = nextFiber()

//       if (fiber == null) {
//         // No available fibers in the local or the external queue.
//         // Try to steal work from other worker threads.
//         fiber = stealWork()
//       }

//       if (fiber == null) {
//         // No fiber has been stolen from any other worker thread.
//         // There is probably not enough work for every thread in
//         // the pool. It's time to park and await a notification
//         // when new work is submitted to the pool.
//         park()
//       } else {
//         // There is a fiber that can be executed, so do it.
//         runFiber(fiber)
//         // Do not forget to null out the reference, so the fiber
//         // can be garbage collected.
//         fiber = null
//       }
//     }
//   }

//   /**
//    * A mechanism for executing support code before executing a blocking action.
//    *
//    * This is a slightly more involved implementation of the support code in
//    * anticipation of running blocking code, also implemented in [[WorkerThread]].
//    *
//    * For a more detailed discussion on the design principles behind the support
//    * for running blocking actions on the [[WorkStealingThreadPool]], check the
//    * code comments for [[HelperThread]].
//    *
//    * The main difference between this and the implementation in [[HelperThread]]
//    * is that [[WorkerThread]]s need to take care of draining their
//    * [[LocalQueue]] to the `overflow` queue before entering the blocking region.
//    *
//    * The reason why this code is duplicated, instead of inherited is to keep the
//    * monomorphic callsites in the `IOFiber` runloop.
//    */
//   override def blockOn[T](thunk: => T)(implicit permission: CanAwait): T = {
//     // Drain the local queue to the `overflow` queue.
//     queue.drain(drain)
//     overflow.addAll(drain)
//     drain.clear()

//     if (blocking) {
//       // This `WorkerThread` is already inside an enclosing blocking region.
//       // There is no need to spawn another `HelperThread`. Instead, directly
//       // execute the blocking action.
//       thunk
//     } else {
//       // Spawn a new `HelperThread` to take the place of this thread, as the
//       // current thread prepares to execute a blocking action.

//       // Logically enter the blocking region.
//       blocking = true

//       // Spawn a new `HelperThread`.
//       val helper = new HelperThread(threadPrefix, blockingThreadCounter, overflow, pool)
//       helper.setName(
//         s"$threadPrefix-blocking-helper-${blockingThreadCounter.incrementAndGet()}")
//       helper.setDaemon(true)
//       helper.start()

//       // With another `HelperThread` started, it is time to execute the blocking
//       // action.
//       val result = thunk

//       // Blocking is finished. Time to signal the spawned helper thread.
//       helper.setSignal()

//       // Do not proceed until the helper thread has fully died. This is terrible
//       // for performance, but it is justified in this case as the stability of
//       // the `WorkStealingThreadPool` is of utmost importance in the face of
//       // blocking, which in itself is **not** what the pool is optimized for.
//       // In practice however, unless looking at a completely pathological case
//       // of propagating blocking actions on every spawned helper thread, this is
//       // not an issue, as the `HelperThread`s are all executing `IOFiber[_]`
//       // instances, which mostly consist of non-blocking code.
//       helper.join()

//       // Logically exit the blocking region.
//       blocking = false

//       // Return the computed result from the blocking operation
//       result
//     }
//   }
// }
