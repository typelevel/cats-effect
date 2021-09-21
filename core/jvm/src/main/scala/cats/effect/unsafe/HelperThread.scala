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

import scala.annotation.tailrec
import scala.concurrent.{BlockContext, CanAwait}

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * A helper thread which is spawned whenever a blocking action is being executed by a
 * [[WorkerThread]]. The purpose of this thread is to continue executing the fibers of the
 * blocked [[WorkerThread]], one of which might ultimately unblock the currently blocked thread.
 * Since all [[WorkerThreads]] drain their local queues before entering a blocking region, the
 * helper threads do not actually steal fibers from the [[WorkerThread]] s. Instead, they
 * operate solely on the `external` queue, where all drained fibers end up, as well as incoming
 * fibers scheduled from outside the runtime. The helper thread loops until the [[WorkerThread]]
 * which spawned it has exited the blocking section (by setting the `signal` variable of this
 * thread). In the case that the `external` queue has been exhausted, the helper thread parks,
 * awaiting further work which may arrive at a later point in time, but could end up unblocking
 * the other threads.
 *
 * The helper thread itself extends [[scala.concurrent.BlockContext]], which means that it also
 * has the ability to anticipate blocking actions. If blocking does occur on a helper thread,
 * another helper thread is started to take its place. Similarly, that thread sticks around
 * until it has been signalled to go away.
 *
 * As for why we're not simply using other [[WorkerThread]] s to take the place of other blocked
 * [[WorkerThreads]], it comes down to optimization and simplicity of implementation. Blocking
 * is simply not expected to occur frequently on the compute pool of Cats Effect, and over time,
 * the users of Cats Effect are expected to learn and use machinery such as `IO.blocking` to
 * properly delineate blocking actions. If blocking were to be anticipated in the
 * [[WorkerThread]] s, their implementation (especially in the trickiest cases of proper
 * finalization of the threads) would be much more complex. This way, both [[WorkerThread]] and
 * [[HelperThread]] get to enjoy a somewhat simpler, more maintainable implementation. The
 * [[WorkStealingThreadPool]] itself is heavily optimized for operating with a fixed number of
 * [[WorkerThread]] instances, and having a dynamic number of [[WorkerThread]] instances
 * introduces more logic on the hot path.
 */
private final class HelperThread(
    private[this] val threadPrefix: String,
    private[this] val blockingThreadCounter: AtomicInteger,
    private[this] val external: ScalQueue[AnyRef],
    private[this] val pool: WorkStealingThreadPool)
    extends Thread
    with BlockContext {

  /**
   * Uncontented source of randomness. By default, `java.util.Random` is thread safe, which is a
   * feature we do not need in this class, as the source of randomness is completely isolated to
   * each instance of `WorkerThread`. The instance is obtained only once at the beginning of
   * this method, to avoid the cost of the `ThreadLocal` mechanism at runtime.
   */
  private[this] var random: ThreadLocalRandom = _

  /**
   * Signalling mechanism through which the [[WorkerThread]] which spawned this [[HelperThread]]
   * signals that it has successfully exited the blocking code region and that this
   * [[HelperThread]] should finalize.
   *
   * This atomic integer encodes a state machine with 3 states. Value 0: the thread is parked
   * Value 1: the thread is unparked and executing fibers Value 2: the thread has been signalled
   * to finish up and exit
   *
   * The thread is spawned in the running state.
   */
  private[this] val signal: AtomicInteger = new AtomicInteger(1)

  /**
   * A flag which is set whenever a blocking code region is entered. This is useful for
   * detecting nested blocking regions, in order to avoid unnecessarily spawning extra
   * [[HelperThread]] s.
   */
  private[this] var blocking: Boolean = false

  // Constructor code.
  {
    // Helper threads are daemon threads.
    setDaemon(true)

    // Set the name of this helper thread.
    setName(s"$threadPrefix-blocking-helper-${blockingThreadCounter.incrementAndGet()}")
  }

  /**
   * Called by the [[WorkerThread]] which spawned this [[HelperThread]], to notify the
   * [[HelperThread]] that the [[WorkerThread]] is finished blocking and is returning to normal
   * operation. The [[HelperThread]] should finalize and die.
   */
  def setSignal(): Unit = {
    signal.set(2)
  }

  /**
   * Schedules a fiber on the `external` queue. [[HelperThread]] s exclusively work with fibers
   * from the `external` queue.
   *
   * @param fiber
   *   the fiber to be scheduled on the `external` queue
   */
  def schedule(fiber: IOFiber[_]): Unit = {
    val rnd = random
    external.offer(fiber, rnd)
    if (!pool.notifyParked(rnd)) {
      pool.notifyHelper(rnd)
    }
  }

  /**
   * Marks the [[HelperThread]] as eligible for resuming work.
   */
  @tailrec
  def unpark(): Unit = {
    if (signal.get() == 0 && !signal.compareAndSet(0, 1))
      unpark()
  }

  /**
   * Checks whether this [[HelperThread]] operates within the [[WorkStealingThreadPool]]
   * provided as an argument to this method. The implementation checks whether the provided
   * [[WorkStealingThreadPool]] matches the reference of the pool provided when this
   * [[HelperThread]] was constructed.
   *
   * @param threadPool
   *   a work stealing thread pool reference
   * @return
   *   `true` if this helper thread is owned by the provided work stealing thread pool, `false`
   *   otherwise
   */
  def isOwnedBy(threadPool: WorkStealingThreadPool): Boolean =
    pool eq threadPool

  /**
   * The run loop of the [[HelperThread]]. A loop iteration consists of checking the `external`
   * queue for available work. If it cannot secure a fiber from the `external` queue, the
   * [[HelperThread]] exits its runloop and dies. If a fiber is secured, it is executed.
   *
   * Each iteration of the loop is preceded with a global check of the status of the pool, as
   * well as a check of the `signal` variable. In the case that any of these two variables have
   * been set by another thread, it is a signal for the [[HelperThread]] to exit its runloop and
   * die.
   */
  override def run(): Unit = {
    random = ThreadLocalRandom.current()
    val rnd = random

    def parkLoop(): Unit = {
      var cont = true
      while (cont && !isInterrupted()) {
        // Park the thread until further notice.
        LockSupport.park(pool)

        // Spurious wakeup check.
        cont = signal.get() == 0
      }
    }

    // Check for exit condition. Do not continue if the `WorkStealingPool` has
    // been shut down, or the `WorkerThread` which spawned this `HelperThread`
    // has finished blocking.
    while (!isInterrupted() && signal.get() != 2) {
      val element = external.poll(rnd)
      if (element.isInstanceOf[Array[IOFiber[_]]]) {
        val batch = element.asInstanceOf[Array[IOFiber[_]]]
        external.offerAll(batch, rnd)
        if (!pool.notifyParked(rnd)) {
          pool.notifyHelper(rnd)
        }
      } else if (element.isInstanceOf[IOFiber[_]]) {
        val fiber = element.asInstanceOf[IOFiber[_]]
        fiber.run()
      } else if (signal.compareAndSet(1, 0)) {
        // There are currently no more fibers available on the external queue.
        // However, the thread that originally started this helper thread has
        // not been unblocked. The fibers that will eventually unblock that
        // original thread might not have arrived on the pool yet. The helper
        // thread should suspend and await for a notification of incoming work.
        pool.transitionHelperToParked(this, rnd)
        pool.notifyIfWorkPending(rnd)
        parkLoop()
      }
    }
  }

  /**
   * A mechanism for executing support code before executing a blocking action.
   *
   * @note
   *   There is no reason to enclose any code in a `try/catch` block because the only way this
   *   code path can be exercised is through `IO.delay`, which already handles exceptions.
   */
  override def blockOn[T](thunk: => T)(implicit permission: CanAwait): T = {
    // Try waking up a `WorkerThread` to handle fibers from the external queue.
    val rnd = random
    if (!pool.notifyParked(rnd)) {
      pool.notifyHelper(rnd)
    }

    if (blocking) {
      // This `HelperThread` is already inside an enclosing blocking region.
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
      pool.removeParkedHelper(helper, rnd)
      helper.setSignal()
      LockSupport.unpark(helper)

      // Logically exit the blocking region.
      blocking = false

      // Return the computed result from the blocking operation
      result
    }
  }
}
