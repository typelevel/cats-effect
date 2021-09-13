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
 * Implementation of the worker thread at the heart of the [[WorkStealingThreadPool]].
 *
 * Each worker thread is assigned exclusive write access to a single [[LocalQueue]] instance of
 * [[IOFiber]] references which other worker threads can steal when they run out of work in
 * their local queue.
 *
 * The existence of multiple work queues dramatically reduces contention in a highly parallel
 * system when compared to a fixed size thread pool whose worker threads all draw tasks from a
 * single global work queue.
 */
private final class WorkerThread(
    // Index assigned by the `WorkStealingThreadPool` for identification purposes.
    private[unsafe] val index: Int,
    // Thread prefix string used for naming new instances of `WorkerThread` and `HelperThread`.
    private[this] val threadPrefix: String,
    // Instance to a global counter used when naming new instances of `HelperThread`.
    private[this] val blockingThreadCounter: AtomicInteger,
    // Local queue instance with exclusive write access.
    private[this] val queue: LocalQueue,
    // The state of the `WorkerThread` (parked/unparked).
    private[this] val parked: AtomicBoolean,
    // External queue used by the local queue for offloading excess fibers, as well as
    // for drawing fibers when the local queue is exhausted.
    private[this] val external: ScalQueue[AnyRef],
    // Reference to the `WorkStealingThreadPool` in which this thread operates.
    private[this] val pool: WorkStealingThreadPool)
    extends Thread
    with BlockContext {

  import WorkStealingThreadPoolConstants._

  /**
   * Uncontented source of randomness. By default, `java.util.Random` is thread safe, which is a
   * feature we do not need in this class, as the source of randomness is completely isolated to
   * each instance of `WorkerThread`. The instance is obtained only once at the beginning of
   * this method, to avoid the cost of the `ThreadLocal` mechanism at runtime.
   */
  private[this] var random: ThreadLocalRandom = _

  /**
   * A flag which is set whenever a blocking code region is entered. This is useful for
   * detecting nested blocking regions, in order to avoid unnecessarily spawning extra
   * [[HelperThread]] s.
   */
  private[this] var blocking: Boolean = false

  /**
   * A mutable reference to a fiber which is used to bypass the local queue when a `cede`
   * operation would enqueue a fiber to the empty local queue and then proceed to dequeue the
   * same fiber again from the queue.
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
   * Schedules the fiber for execution at the back of the local queue and notifies the work
   * stealing pool of newly available work.
   *
   * @param fiber
   *   the fiber to be scheduled on the local queue
   */
  def schedule(fiber: IOFiber[_]): Unit = {
    val rnd = random
    queue.enqueue(fiber, external, rnd)
    pool.notifyParked(rnd)
    ()
  }

  /**
   * Specifically supports the `cede` and `autoCede` mechanisms of the [[cats.effect.IOFiber]]
   * runloop. In the case where the local queue is empty prior to enqueuing the argument fiber,
   * the local queue is bypassed, which saves a lot of unnecessary atomic load/store operations
   * as well as a costly wake up of another thread for which there is no actual work. On the
   * other hand, if the local queue is not empty, this method enqueues the argument fiber on the
   * local queue, wakes up another thread to potentially help out with the available fibers and
   * continues with the worker thread run loop.
   *
   * @param fiber
   *   the fiber that `cede`s/`autoCede`s
   */
  def reschedule(fiber: IOFiber[_]): Unit = {
    if ((cedeBypass eq null) && queue.isEmpty()) {
      cedeBypass = fiber
    } else {
      schedule(fiber)
    }
  }

  /**
   * Checks whether this [[WorkerThread]] operates within the [[WorkStealingThreadPool]]
   * provided as an argument to this method. The implementation checks whether the provided
   * [[WorkStealingThreadPool]] matches the reference of the pool provided when this
   * [[WorkerThread]] was constructed.
   *
   * @note
   *   When blocking code is being executed on this worker thread, it is important to delegate
   *   all scheduling operation to the external queue from which all [[HelperThread]] instances
   *   operate.
   *
   * @param threadPool
   *   a work stealing thread pool reference
   * @return
   *   `true` if this worker thread is owned by the provided work stealing thread pool, `false`
   *   otherwise
   */
  def isOwnedBy(threadPool: WorkStealingThreadPool): Boolean =
    (pool eq threadPool) && !blocking

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
     *      `OverflowQueueTicks` number of iterations, the external queue takes
     *      precedence over the local queue.
     *
     *      If a fiber is successfully dequeued from the external queue, it will
     *      be executed immediately. If a batch of fibers is dequeued instead,
     *      the whole batch is enqueued on the local queue and other worker
     *      threads are notified of existing work available for stealing. The
     *      `WorkerThread` unconditionally transitions to executing fibers from
     *      the local queue (state value 4 and larger).
     *
     *      This state occurs "naturally" after a certain number of executions
     *      from the local queue (when the state value wraps around modulo
     *      `OverflowQueueTicks`).
     *
     *   1: Fall back to checking the external queue after a failed dequeue from
     *      the local queue. Depending on the outcome of this check, the
     *      `WorkerThread` transitions to executing fibers from the local queue
     *      in the case of a successful dequeue from the external queue (state
     *      value 4 and larger). Otherwise, the `WorkerThread` continues with
     *      asking for permission to steal from other `WorkerThread`s.
     *
     *      Depending on the outcome of this request, the `WorkerThread` starts
     *      looking for fibers to steal from the local queues of other worker
     *      threads (if permission was granted, state value 2), or parks
     *      directly. In this case, there is less bookkeeping to be done
     *      compared to the case where a worker was searching for work prior to
     *      parking. After the worker thread has been unparked, it transitions
     *      to looking for work in the external queue (state value 3) while also
     *      holding a permission to steal fibers from other worker threads.
     *
     *   2: The `WorkerThread` has been allowed to steal fibers from other
     *      worker threads. If the attempt is successful, the first fiber is
     *      executed directly and the `WorkerThread` transitions to executing
     *      fibers from the local queue (state value 4 and larger). If the
     *      attempt is unsuccessful, the worker thread announces to the pool
     *      that it was unable to find any work and parks.
     *
     *   3: The `WorkerThread` has been unparked an is looking for work in the
     *      external queue. If it manages to find work there, it announces to
     *      the work stealing thread pool that it is no longer searching for
     *      work and continues to execute fibers from the local queue (state
     *      value 4 and larger). Otherwise, it transitions to searching for work
     *      to steal from the local queues of other worker threads because the
     *      permission to steal is implicitly held by threads that have been
     *      unparked (state value 2).
     *
     *   4 and larger: Look for fibers to execute in the local queue. In case
     *      of a successful dequeue from the local queue, increment the state
     *      value. In case of a failed dequeue from the local queue, transition
     *      to looking for fibers in the external queue (state value 1).
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
          // Obtain a fiber or batch of fibers from the external queue.
          val element = external.poll(rnd)
          if (element.isInstanceOf[Array[IOFiber[_]]]) {
            val batch = element.asInstanceOf[Array[IOFiber[_]]]
            // The dequeued element was a batch of fibers. Enqueue the whole
            // batch on the local queue and execute the first fiber.

            // Make room for the batch if the local queue cannot accomodate
            // all of the fibers as is.
            queue.drainBatch(external, rnd)

            val fiber = queue.enqueueBatch(batch)
            // Many fibers have been exchanged between the external and the
            // local queue. Notify other worker threads.
            pool.notifyParked(rnd)
            fiber.run()
          } else if (element.isInstanceOf[IOFiber[_]]) {
            val fiber = element.asInstanceOf[IOFiber[_]]
            // The dequeued element is a single fiber. Execute it immediately.
            fiber.run()
          }

          // Transition to executing fibers from the local queue.
          state = 4

        case 1 =>
          // Check the external queue after a failed dequeue from the local
          // queue (due to the local queue being empty).
          val element = external.poll(rnd)
          if (element.isInstanceOf[Array[IOFiber[_]]]) {
            val batch = element.asInstanceOf[Array[IOFiber[_]]]
            // The dequeued element was a batch of fibers. Enqueue the whole
            // batch on the local queue and execute the first fiber.
            // It is safe to directly enqueue the whole batch because we know
            // that in this state of the worker thread state machine, the
            // local queue is empty.
            val fiber = queue.enqueueBatch(batch)
            // Many fibers have been exchanged between the external and the
            // local queue. Notify other worker threads.
            pool.notifyParked(rnd)
            fiber.run()

            // Transition to executing fibers from the local queue.
            state = 4
          } else if (element.isInstanceOf[IOFiber[_]]) {
            val fiber = element.asInstanceOf[IOFiber[_]]
            // The dequeued element is a single fiber. Execute it immediately.
            fiber.run()

            // Transition to executing fibers from the local queue.
            state = 4
          } else {
            // Could not find any fibers in the external queue. Proceed to ask
            // for permission to steal fibers from other `WorkerThread`s.
            if (pool.transitionWorkerToSearching()) {
              // Permission granted, proceed to steal.
              state = 2
            } else {
              // Permission denied, proceed to park.
              // Set the worker thread parked signal.
              parked.lazySet(true)
              // Announce that the worker thread is parking.
              pool.transitionWorkerToParked()
              // Park the thread.
              parkLoop()
              // After the worker thread has been unparked, look for work in the
              // external queue.
              state = 3
            }
          }

        case 2 =>
          // Try stealing fibers from other worker threads.
          val fiber = pool.stealFromOtherWorkerThread(index, rnd)
          if (fiber ne null) {
            // Successful steal. Announce that the current thread is no longer
            // looking for work.
            pool.transitionWorkerFromSearching(rnd)
            // Run the stolen fiber.
            fiber.run()
            // Transition to executing fibers from the local queue.
            state = 4
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
              // local queues or new work might have arrived on the external
              // queue. Another thread might be able to help.
              pool.notifyIfWorkPending(rnd)
            }
            // Park the thread.
            parkLoop()
            // After the worker thread has been unparked, look for work in the
            // external queue.
            state = 3
          }

        case 3 =>
          // Check the external queue after a failed dequeue from the local
          // queue (due to the local queue being empty).
          val element = external.poll(rnd)
          if (element ne null) {
            // Announce that the current thread is no longer looking for work.
            pool.transitionWorkerFromSearching(rnd)

            if (element.isInstanceOf[Array[IOFiber[_]]]) {
              // The dequeued element was a batch of fibers. Enqueue the whole
              // batch on the local queue and execute the first fiber.
              // It is safe to directly enqueue the whole batch because we know
              // that in this state of the worker thread state machine, the
              // local queue is empty.
              val fiber = queue.enqueueBatch(element.asInstanceOf[Array[IOFiber[_]]])
              // Many fibers have been exchanged between the external and the
              // local queue. Notify other worker threads.
              pool.notifyParked(rnd)
              fiber.run()
            } else {
              // The dequeued element is a single fiber. Execute it immediately.
              element.asInstanceOf[IOFiber[_]].run()
            }

            // Transition to executing fibers from the local queue.
            state = 4
          } else {
            // Transition to stealing fibers from other `WorkerThread`s.
            // The permission is held implicitly by threads right after they
            // have been woken up.
            state = 2
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
            // Transition to checking the external queue.
            state = 1
          }
      }
    }
  }

  /**
   * A mechanism for executing support code before executing a blocking action.
   *
   * This is a slightly more involved implementation of the support code in anticipation of
   * running blocking code, also implemented in [[HelperThread]].
   *
   * For a more detailed discussion on the design principles behind the support for running
   * blocking actions on the [[WorkStealingThreadPool]], check the code comments for
   * [[HelperThread]].
   *
   * The main difference between this and the implementation in [[HelperThread]] is that
   * [[WorkerThread]] s need to take care of draining their [[LocalQueue]] to the `external`
   * queue before entering the blocking region.
   *
   * The reason why this code is duplicated, instead of inherited is to keep the monomorphic
   * callsites in the `IOFiber` runloop.
   *
   * @note
   *   There is no reason to enclose any code in a `try/catch` block because the only way this
   *   code path can be exercised is through `IO.delay`, which already handles exceptions.
   */
  override def blockOn[T](thunk: => T)(implicit permission: CanAwait): T = {
    // Drain the local queue to the `external` queue.
    val rnd = random
    val drain = queue.drain()
    external.offerAll(drain, rnd)
    val cedeFiber = cedeBypass
    if (cedeFiber ne null) {
      cedeBypass = null
      external.offer(cedeFiber, rnd)
    }

    if (!pool.notifyParked(rnd)) {
      pool.notifyHelper(rnd)
    }

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
        new HelperThread(threadPrefix, blockingThreadCounter, external, pool)
      helper.start()

      // With another `HelperThread` started, it is time to execute the blocking
      // action.
      val result = thunk

      // Blocking is finished. Time to signal the spawned helper thread and
      // unpark it. Furthermore, the thread needs to be removed from the
      // parked helper threads queue in the pool so that other threads don't
      // mistakenly depend on it to bail them out of blocking situations, and
      // of course, this also removes the last strong reference to the fiber,
      // which needs to be released for gc purposes.
      pool.removeParkedHelper(helper, random)
      helper.setSignal()
      LockSupport.unpark(helper)

      // Logically exit the blocking region.
      blocking = false

      // Return the computed result from the blocking operation
      result
    }
  }
}
