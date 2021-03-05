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
    if ((cedeBypass eq null) && queue.isEmpty()) {
      cedeBypass = fiber
    } else {
      schedule(fiber)
    }
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
          val fiber = overflow.poll()
          if (fiber ne null) {
            // Run the fiber.
            fiber.run()
          }
          // Transition to executing fibers from the local queue.
          state = 7

        case 1 =>
          val fiber = overflow.poll()
          if (fiber ne null) {
            // Run the fiber.
            fiber.run()
            // Transition to executing fibers from the local queue.
            state = 7
          } else {
            // Ask for permission to steal fibers from other `WorkerThread`s.
            state = 2
          }

        case 2 =>
          if (pool.transitionWorkerToSearching()) {
            state = 3
          } else {
            state = 4
          }

        case 3 =>
          // This thread has been allowed to steal work from other workers.
          val fiber = pool.stealFromOtherWorkerThread(index, random.nextInt(threadCount))
          if (fiber ne null) {
            // Run the stolen fiber.
            pool.transitionWorkerFromSearching()
            fiber.run()
            // Transition to executing fibers from the local queue.
            state = 7
          } else {
            // Stealing attempt is unsuccessful. Park.
            state = 5
          }

        case 4 =>
          parked.lazySet(true)
          pool.transitionWorkerToParked(this)
          parkLoop()
          state = 6

        case 5 =>
          parked.lazySet(true)
          if (pool.transitionWorkerToParkedWhenSearching(this)) {
            pool.notifyIfWorkPending()
          }
          parkLoop()
          state = 6

        case 6 =>
          val fiber = overflow.poll()
          if (fiber ne null) {
            pool.transitionWorkerFromSearching()
            // Run the fiber.
            fiber.run()
            // Transition to executing fibers from the local queue.
            state = 7
          } else {
            // Transition to stealing fibers from other `WorkerThread`s.
            state = 3
          }

        case _ =>
          // Check the queue bypass reference before dequeueing from the local
          // queue.
          val fiber = if (cedeBypass eq null) {
            queue.dequeue()
          } else {
            val f = cedeBypass
            cedeBypass = null
            f
          }
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
