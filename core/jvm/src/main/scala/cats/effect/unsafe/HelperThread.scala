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
 * A helper thread which is spawned whenever a blocking action is being executed
 * by a [[WorkerThread]]. The purpose of this thread is to continue executing
 * the fibers of the blocked [[WorkerThread]], one of which might ultimately
 * unblock the currently blocked thread. Since all [[WorkerThreads]] drain their
 * local queues before entering a blocking region, the helper threads do not
 * actually steal fibers from the [[WorkerThread]]s. Instead, they operate
 * solely on the `overflow` queue, where all drained fibers end up, as well as
 * incoming fibers scheduled from outside the runtime. The helper thread loops
 * until the [[WorkerThread]] which spawned it has exited the blocking section
 * (by setting the `signal` variable of this thread), or until the `overflow`
 * queue has been exhausted, whichever comes first.
 *
 * The helper thread itself extends [[scala.concurrent.BlockContext]], which
 * means that it also has the ability to anticipate blocking actions. If
 * blocking does occur on a helper thread, another helper thread is started to
 * take its place. Similarly, that thread sticks around until it has been
 * signalled to go away, or the `overflow` queue has been exhausted.
 *
 * As for why we're not simply using other [[WorkerThread]]s to take the place
 * of other blocked [[WorkerThreads]], it comes down to optimization and
 * simplicity of implementation. Blocking is simply not expected to occur
 * frequently on the compute pool of Cats Effect, and over time, the users of
 * Cats Effect are expected to learn and use machinery such as `IO.blocking` to
 * properly delineate blocking actions. If blocking were to be anticipated in
 * the [[WorkerThread]]s, their implementation (especially in the trickiest
 * cases of proper finalization of the threads) would be much more complex. This
 * way, both [[WorkerThread]] and [[HelperThread]] get to enjoy a somewhat
 * simpler, more maintainable implementation. The [[WorkStealingThreadPool]]
 * itself is heavily optimized for operating with a fixed number of
 * [[WorkerThread]]s, and having a dynamic number of [[WorkerThread]] instances
 * introduces more logic on the hot path.
 */
private final class HelperThread(
    private[this] val threadPrefix: String,
    private[this] val blockingThreadCounter: AtomicInteger,
    private[this] val batched: ScalQueue[Array[IOFiber[_]]],
    private[this] val overflow: ScalQueue[IOFiber[_]],
    private[this] val pool: WorkStealingThreadPool)
    extends Thread
    with BlockContext {

  /**
   * Uncontented source of randomness. By default, `java.util.Random` is thread
   * safe, which is a feature we do not need in this class, as the source of
   * randomness is completely isolated to each instance of `WorkerThread`. The
   * instance is obtained only once at the beginning of this method, to avoid
   * the cost of the `ThreadLocal` mechanism at runtime.
   */
  private[this] var random: ThreadLocalRandom = _

  /**
   * Signalling mechanism through which the [[WorkerThread]] which spawned this
   * [[HelperThread]] signals that it has successfully exited the blocking code
   * region and that this [[HelperThread]] should finalize.
   */
  private[this] val signal: AtomicInteger = new AtomicInteger(1)

  /**
   * A flag which is set whenever a blocking code region is entered. This is
   * useful for detecting nested blocking regions, in order to avoid
   * unnecessarily spawning extra [[HelperThread]]s.
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
   * Called by the [[WorkerThread]] which spawned this [[HelperThread]], to
   * notify the [[HelperThread]] that the [[WorkerThread]] is finished blocking
   * and is returning to normal operation. The [[HelperThread]] should finalize
   * and die.
   */
  def setSignal(): Unit = {
    signal.set(2)
  }

  /**
   * Schedules a fiber on the `overflow` queue. [[HelperThread]]s exclusively
   * work with fibers from the `overflow` queue.
   *
   * @param fiber the fiber to be scheduled on the `overflow` queue
   */
  def schedule(fiber: IOFiber[_]): Unit = {
    val rnd = random
    overflow.offer(fiber, rnd)
    if (!pool.notifyParked(rnd)) {
      pool.notifyHelper(rnd)
    }
  }

  @tailrec
  def unpark(): Unit = {
    if (signal.get() == 0 && !signal.compareAndSet(0, 1))
      unpark()
  }

  /**
   * Checks whether this [[HelperThread]] operates within the
   * [[WorkStealingThreadPool]] provided as an argument to this method. The
   * implementation checks whether the provided [[WorkStealingThreadPool]]
   * matches the reference of the pool provided when this [[HelperThread]] was
   * constructed.
   *
   * @param threadPool a work stealing thread pool reference
   * @return `true` if this helper thread is owned by the provided work stealing
   *         thread pool, `false` otherwise
   */
  def isOwnedBy(threadPool: WorkStealingThreadPool): Boolean =
    pool eq threadPool

  /**
   * The run loop of the [[HelperThread]]. A loop iteration consists of
   * checking the `overflow` queue for available work. If it cannot secure a
   * fiber from the `overflow` queue, the [[HelperThread]] exits its runloop
   * and dies. If a fiber is secured, it is executed.
   *
   * Each iteration of the loop is preceded with a global check of the status
   * of the pool, as well as a check of the `signal` variable. In the case that
   * any of these two variables have been set by another thread, it is a signal
   * for the [[HelperThread]] to exit its runloop and die.
   */
  override def run(): Unit = {
    random = ThreadLocalRandom.current()
    val rnd = random

    def parkLoop(): Unit = {
      var cont = true
      while (cont && !isInterrupted()) {
        LockSupport.park(pool)

        cont = signal.get() == 0
      }
    }

    // Check for exit condition. Do not continue if the `WorkStealingPool` has
    // been shut down, or the `WorkerThread` which spawned this `HelperThread`
    // has finished blocking.
    while (!isInterrupted() && signal.get() != 2) {
      // Check the batched queue.
      val batch = batched.poll(rnd)
      if (batch ne null) {
        overflow.offerAll(batch, rnd)
        if (!pool.notifyParked(rnd)) {
          pool.notifyHelper(rnd)
        }
      }

      val fiber = overflow.poll(rnd)
      if (fiber ne null) {
        fiber.run()
      } else if (signal.compareAndSet(1, 0)) {
        pool.transitionHelperToParked(this, rnd)
        pool.notifyIfWorkPending(rnd)
        parkLoop()
      }
    }
  }

  /**
   * A mechanism for executing support code before executing a blocking action.
   */
  override def blockOn[T](thunk: => T)(implicit permission: CanAwait): T = {
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
        new HelperThread(threadPrefix, blockingThreadCounter, batched, overflow, pool)
      helper.start()

      // With another `HelperThread` started, it is time to execute the blocking
      // action.
      val result = thunk

      // Blocking is finished. Time to signal the spawned helper thread.
      pool.removeParkedHelper(helper, random)
      helper.setSignal()
      LockSupport.unpark(helper)

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
