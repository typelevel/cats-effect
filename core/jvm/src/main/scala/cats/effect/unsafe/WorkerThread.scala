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

package cats.effect
package unsafe

import scala.annotation.switch
import scala.concurrent.{BlockContext, CanAwait}

import java.util.concurrent.ThreadLocalRandom
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
private final class WorkerThread(
    // Index assigned by the `WorkStealingThreadPool` for identification purposes.
    private[unsafe] val index: Int,
    // Thread prefix string used for naming new instances of `WorkerThread` and `HelperThread`.
    private[this] val threadPrefix: String,
    // Instance to a global counter used when naming new instances of `HelperThread`.
    private[this] val blockingThreadCounter: AtomicInteger,
    // Instance to a global gauge used for tracking the number of active `HelperThread`s.
    private[this] val activeHelperThreadGauge: AtomicInteger,
    // Local queue instance with exclusive write access.
    private[this] val queue: LocalQueue,
    // The state of the `WorkerThread` (parked/unparked).
    private[this] val parked: AtomicBoolean,
    private[this] val batched: ScalQueue[Array[IOFiber[_]]],
    // Overflow queue used by the local queue for offloading excess fibers, as well as
    // for drawing fibers when the local queue is exhausted.
    private[this] val overflow: ScalQueue[IOFiber[_]],
    // Reference to the `WorkStealingThreadPool` in which this thread operates.
    private[this] val pool: WorkStealingThreadPool)
    extends Thread
    with BlockContext {

  import LocalQueueConstants._
  import WorkStealingThreadPoolConstants._

  /**
   * Uncontented source of randomness. By default, `java.util.Random` is thread
   * safe, which is a feature we do not need in this class, as the source of
   * randomness is completely isolated to each instance of `WorkerThread`. The
   * instance is obtained only once at the beginning of this method, to avoid
   * the cost of the `ThreadLocal` mechanism at runtime.
   */
  private[this] var random: ThreadLocalRandom = _

  /**
   * A flag which is set whenever a blocking code region is entered. This is
   * useful for detecting nested blocking regions, in order to avoid
   * unnecessarily spawning extra [[HelperThread]]s.
   */
  private[this] var blocking: Boolean = false

  /**
   * A mutable reference to a fiber which is used to bypass the local queue when
   * a `cede` operation would enqueue a fiber to the empty local queue and then
   * proceed to dequeue the same fiber again from the queue.
   */
  private[this] var cedeBypass: IOFiber[_] = null

  // Constructor code.
  {
    // Worker threads are daemon threads.
    setDaemon(true)

    // Set the name of this thread.
    setName(s"$threadPrefix-$index")
  }

  /**
   * Schedules the fiber for execution at the back of the local queue and
   * notifies the work stealing pool of newly available work.
   *
   * @param fiber the fiber to be scheduled on the local queue
   */
  def schedule(fiber: IOFiber[_]): Unit = {
    val rnd = random
    queue.enqueue(fiber, batched, overflow, rnd)
    pool.notifyParked(rnd)
    ()
  }

  /**
   * Specifically supports the `cede` and `autoCede` mechanisms of the
   * [[cats.effect.IOFiber]] runloop. In the case where the local queue is
   * empty prior to enqueuing the argument fiber, the local queue is bypassed,
   * which saves a lot of unnecessary atomic load/store operations as well as a
   * costly wake up of another thread for which there is no actual work. On the
   * other hand, if the local queue is not empty, this method enqueues the
   * argument fiber on the local queue, wakes up another thread to potentially
   * help out with the available fibers and continues with the worker thread run
   * loop.
   *
   * @param fiber the fiber that `cede`s/`autoCede`s
   */
  def reschedule(fiber: IOFiber[_]): Unit = {
    if ((cedeBypass eq null) && queue.isEmpty()) {
      cedeBypass = fiber
    } else {
      schedule(fiber)
    }
  }

  /**
   * The run loop of the [[WorkerThread]].
   */
  override def run(): Unit = {
    random = ThreadLocalRandom.current()
    val rnd = random

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
     *      be executed. The `WorkerThread` unconditionally transitions to
     *      executing fibers from the local queue (state value 7 and larger).
     *
     *      This state occurs "naturally" after a certain number of executions
     *      from the local queue (when the state value wraps around modulo
     *      `OverflowQueueTicks`).
     *
     *   1: Fall back to checking the batched queue after a failed dequeue from
     *      the local queue. Depending on the outcome of this check, the
     *      `WorkerThread` transitions to executing fibers from the local queue
     *      in the case of a successful dequeue from the batched queue and
     *      subsequent bulk enqueue of the batch to the local queue (state value
     *      7 and larger). Otherwise, the `WorkerThread` transitions to looking
     *      for single fibers in the overflow queue.
     *
     *   2: Fall back to checking the overflow queue after a failed dequeue from
     *      the local queue. Depending on the outcome of this check, the
     *      `WorkerThread` transitions to executing fibers from the local queue
     *      in the case of a successful dequeue from the overflow queue
     *      (state value 7 and larger). Otherwise, the `WorkerThread`
     *      transitions to asking for permission to steal from other
     *      `WorkerThread`s (state value 3).
     *
     *   3: Ask for permission to steal fibers from other `WorkerThread`s.
     *      Depending on the outcome, the `WorkerThread` transitions starts
     *      looking for fibers to steal from the local queues of other
     *      worker threads (permission granted, state value 4), or parks
     *      directly. In this case, there is less bookkeeping to be done
     *      compared to the case where a worker was searching for work prior
     *      to parking. After the worker thread has been unparked, it
     *      transitions to looking for work in the batched (state value 5) and
     *      overflow queues (state value 6) while also holding a permission to
     *      steal fibers from other worker threads.
     *
     *   4: The `WorkerThread` has been allowed to steal fibers from other
     *      worker threads. If the attempt is successful, the first fiber is
     *      executed directly and the `WorkerThread` transitions to executing
     *      fibers from the local queue (state value 7 and larger). If the
     *      attempt is unsuccessful, the worker thread announces to the pool
     *      that it was unable to find any work and parks. After the worker
     *      thread has been unparked, it transitions to looking for work in the
     *      batched (state value 5) and overflow queues (state value 6) while
     *      also holding a permission to steal fibers from other worker threads.
     *
     *   5: State after a worker thread has been unparked. In this state, the
     *      permission to steal from other worker threads is implicitly held.
     *      The unparked worker thread starts by looking for work in the
     *      batched queue. If a fiber has been found, it is executed and the
     *      worker thread transitions to executing fibers from the local queue
     *      (state value 7 and larger). If no fiber has been found, the worker
     *      thread proceeds to look for single fibers in the overflow queue
     *      (state value 6).
     *
     *   6: In this state, the permission to steal from other worker threads is
     *      implicitly held. If a fiber has been found in the overflow queue, it
     *      is executed and the worker thread transitions to executing fibers
     *      from the local queue (state value 7 and larger). If no fiber has
     *      been found, the worker thread proceeds to steal work from other
     *      worker threads (since it already has the permission to do so by
     *      convention) (state value 4).
     *
     *   7 and larger: Look for fibers to execute in the local queue. In case
     *      of a successful dequeue from the local queue, increment the state
     *      value. In case of a failed dequeue from the local queue, transition
     *      to looking for fibers in the batched queue (state value 1).
     *
     * A note on the implementation. Some of the states seem like they have
     * overlapping logic. This is indeed true, but it is a conscious decision.
     * The logic is carefully unrolled and compiled into a shallow `tableswitch`
     * instead of a deeply nested sequence of `if/else` statements. This change
     * has lead to a non-negligible 15-20% increase in single-threaded
     * performance.
     */
    var state = 0

    def parkLoop(): Unit = {
      var cont = true
      while (cont && !isInterrupted()) {
        // Park the thread until further notice.
        LockSupport.park(pool)

        // Spurious wakeup check.
        cont = parked.get()
      }
    }

    while (!isInterrupted()) {
      ((state & OverflowQueueTicksMask): @switch) match {
        case 0 =>
          // Dequeue a fiber from the overflow queue.
          val fiber = overflow.poll(rnd)
          if (fiber ne null) {
            // Run the fiber.
            fiber.run()
          }
          // Transition to executing fibers from the local queue.
          state = 7

        case 1 =>
          // Try to get a batch of fibers to execute from the batched queue
          // after a failed dequeue from the local queue.
          val batch = batched.poll(rnd)
          if (batch ne null) {
            // A batch of fibers has been successfully obtained. Proceed to
            // enqueue all of the fibers on the local queue and execute the
            // first one.
            val fiber = queue.enqueueBatch(batch)
            // Many fibers have been enqueued on the local queue. Notify other
            // worker threads.
            pool.notifyParked(rnd)
            // Directly run a fiber from the batch.
            fiber.run()
            // Transition to executing fibers from the local queue.
            state = 7
          } else {
            // Could not obtain a batch of fibers. Proceed to check for single
            // fibers in the overflow queue.
            state = 2
          }

        case 2 =>
          // Dequeue a fiber from the overflow queue after a failed attempt to
          // secure a batch of fibers.
          val fiber = overflow.poll(rnd)
          if (fiber ne null) {
            // Run the fiber.
            fiber.run()
            // Transition to executing fibers from the local queue.
            state = 7
          } else {
            // Ask for permission to steal fibers from other `WorkerThread`s.
            state = 3
          }

        case 3 =>
          // Ask for permission to steal fibers from other `WorkerThread`s.
          if (pool.transitionWorkerToSearching()) {
            // Permission granted, proceed to stealing.
            state = 4
          } else {
            // Permission denied, proceed to park.
            // Set the worker thread parked signal.
            parked.lazySet(true)
            // Announce that the worker thread is parking.
            pool.transitionWorkerToParked()
            // Park the thread.
            parkLoop()
            // After the worker thread has been unparked, look for work in the
            // batched queue.
            state = 5
          }

        case 4 =>
          // Try stealing fibers from other worker threads.
          val fiber = pool.stealFromOtherWorkerThread(index, rnd)
          if (fiber ne null) {
            // Successful steal. Announce that the current thread is no longer
            // looking for work.
            pool.transitionWorkerFromSearching(rnd)
            // Run the stolen fiber.
            fiber.run()
            // Transition to executing fibers from the local queue.
            state = 7
          } else {
            // Stealing attempt is unsuccessful. Park.
            // Set the worker thread parked signal.
            parked.lazySet(true)
            // Announce that the worker thread which was searching for work is now
            // parking. This checks if the parking worker thread was the last
            // actively searching thread.
            if (pool.transitionWorkerToParkedWhenSearching()) {
              // If this was indeed the last actively searching thread, do another
              // global check of the pool. Other threads might be busy with their
              // local queues or new work might have arrived on the overflow
              // queue. Another thread might be able to help.
              pool.notifyIfWorkPending(rnd)
            }
            // Park the thread.
            parkLoop()
            // After the worker thread has been unparked, look for work in the
            // batched queue.
            state = 5
          }

        case 5 =>
          // Try to get a batch of fibers to execute from the batched queue.
          val batch = batched.poll(rnd)
          if (batch ne null) {
            // A batch of fibers has been successfully obtained. Proceed to
            // enqueue all of the fibers on the local queue and execute the
            // first one.
            val fiber = queue.enqueueBatch(batch)
            // Not searching for work anymore. As a bonus, if this was indeed
            // the last searching worker thread, this will wake up another
            // thread to help out with the newly acquired fibers.
            pool.transitionWorkerFromSearching(rnd)
            // Directly run a fiber from the batch.
            fiber.run()
            // Transition to executing fibers from the local queue.
            state = 7
          } else {
            // Could not obtain a batch of fibers. Proceed to check for single
            // fibers in the overflow queue.
            state = 6
          }

        case 6 =>
          // Dequeue a fiber from the overflow queue.
          val fiber = overflow.poll(rnd)
          if (fiber ne null) {
            // Announce that the current thread is no longer looking for work.
            pool.transitionWorkerFromSearching(rnd)
            // Run the fiber.
            fiber.run()
            // Transition to executing fibers from the local queue.
            state = 7
          } else {
            // Transition to stealing fibers from other `WorkerThread`s.
            // The permission is held implicitly by threads right after they
            // have been woken up.
            state = 4
          }

        case _ =>
          // Check the queue bypass reference before dequeueing from the local
          // queue.
          val fiber = if (cedeBypass eq null) {
            // The queue bypass reference is empty.
            // Fall back to the local queue.
            queue.dequeue()
          } else {
            // Fetch and null out the queue bypass reference.
            val f = cedeBypass
            cedeBypass = null
            f
          }
          if (fiber ne null) {
            // Run the fiber.
            fiber.run()
            // Continue executing fibers from the local queue.
            state += 1
          } else {
            // Transition to checking the batched queue.
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
    // Try waking up a `WorkerThread` to steal fibers from the `LocalQueue` of
    // this thread.
    val rnd = random
    if (pool.notifyParked(rnd)) {
      // Successfully woke up another `WorkerThread` to help out with the
      // anticipated blocking. Even if this thread ends up being blocked for
      // some time, the other worker would be able to steal its fibers.

      if (blocking) {
        // This `WorkerThread` is already inside an enclosing blocking region.
        thunk
      } else {
        // Logically enter the blocking region.
        blocking = true

        val result = thunk

        // Logically exit the blocking region.
        blocking = false

        // Return the computed result from the blocking operation.
        result
      }
    } else {
      // Drain the local queue to the `overflow` queue.
      val drain = new Array[IOFiber[_]](LocalQueueCapacity)
      queue.drain(drain)
      overflow.offerAll(drain, random)

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
        val helper =
          new HelperThread(
            threadPrefix,
            blockingThreadCounter,
            activeHelperThreadGauge,
            batched,
            overflow,
            pool)
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
        try helper.join()
        catch {
          case _: InterruptedException =>
            // Propagate interruption to the helper thread.
            Thread.interrupted()
            helper.interrupt()
            helper.join()
            this.interrupt()
        }

        // Logically exit the blocking region.
        blocking = false

        // Return the computed result from the blocking operation
        result
      }
    }
  }
}
