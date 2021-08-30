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
package testkit

import cats.effect.kernel.testkit.TestContext
import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}

import scala.concurrent.duration.FiniteDuration

/**
 * Implements a fully functional single-threaded runtime for [[cats.effect.IO]].
 * When using the [[runtime]] provided by this type, `IO` programs will be
 * executed on a single JVM thread, ''similar'' to how they would behave if the
 * production runtime were configured to use a single worker thread regardless
 * of underlying physical thread count. Calling one of the `unsafeRun` methods
 * on an `IO` will submit it to the runtime for execution, but nothing will
 * actually evaluate until one of the ''tick'' methods on this class are called.
 * If the desired behavior is to simply run the `IO` fully to completion within
 * the mock environment, respecting monotonic time, then [[tickAll]] is likely
 * the desired method.
 *
 * Where things ''differ'' from the production runtime is in two critical areas.
 *
 * First, whenever multiple fibers are outstanding and ready to be resumed, the
 * `TestControl` runtime will ''randomly'' choose between them, rather than taking
 * them in a first-in, first-out fashion as the default production runtime will.
 * This semantic is intended to simulate different scheduling interleavings,
 * ensuring that race conditions are not accidentally masked by deterministic
 * execution order.
 *
 * Second, within the context of the `TestControl`, ''time'' is very carefully
 * and artificially controlled. In a sense, this runtime behaves as if it is
 * executing on a single CPU which performs all operations infinitely fast.
 * Any fibers which are immediately available for execution will be executed
 * until no further fibers are available to run (assuming the use of `tickAll`).
 * Through this entire process, the current clock (which is exposed to the
 * program via [[IO.realTime]] and [[IO.monotonic]]) will remain fixed at the
 * very beginning, meaning that no time is considered to have elapsed as a
 * consequence of ''compute''.
 *
 * Note that the above means that it is relatively easy to create a deadlock
 * on this runtime with a program which would not deadlock on either the JVM
 * or JavaScript:
 *
 * {{{
 *   // do not do this!
 *   IO.cede.foreverM.timeout(10.millis)
 * }}}
 *
 * The above program spawns a fiber which yields forever, setting a timeout
 * for 10 milliseconds which is ''intended'' to bring the loop to a halt.
 * However, because the immediate task queue will never be empty, the test
 * runtime will never advance time, meaning that the 10 milliseconds will
 * never elapse and the timeout will not be hit. This will manifest as the
 * [[tick]] and [[tickAll]] functions simply running forever and not returning
 * if called. [[tickOne]] is safe to call on the above program, but it will
 * always return `true`.
 *
 * In order to advance time, you must use the [[advance]] method to move the
 * clock forward by a specified offset (which must be greater than 0). If you
 * use the `tickAll` method, the clock will be automatically advanced by the
 * minimum amount necessary to reach the next pending task. For example, if
 * the program contains an [[IO.sleep]] for `500.millis`, and there are no
 * shorter sleeps, then time will need to be advanced by 500 milliseconds in
 * order to make that fiber eligible for execution.
 *
 * At this point, the process repeats until all tasks are exhausted. If the
 * program has reached a concluding value or exception, then it will be
 * produced from the `unsafeRun` method which scheduled the `IO` on the
 * runtime (pro tip: do ''not'' use `unsafeRunSync` with this runtime, since
 * it will always result in immediate deadlock). If the program does ''not''
 * produce a result but also has no further work to perform (such as a
 * program like [[IO.never]]), then `tickAll` will return but no result will
 * have been produced by the `unsafeRun`. If this happens, [[isDeadlocked]]
 * will return `true` and the program is in a "hung" state. This same situation
 * on the production runtime would have manifested as an asynchronous
 * deadlock.
 *
 * You should ''never'' use this runtime in a production code path. It is
 * strictly meant for testing purposes, particularly testing programs that
 * involve time functions and [[IO.sleep]].
 *
 * Due to the semantics of this runtime, time will behave entirely consistently
 * with a plausible production execution environment provided that you ''never''
 * observe time via side-effects, and exclusively through the [[IO.realTime]],
 * [[IO.monotonic]], and [[IO.sleep]] functions (and other functions built on
 * top of these). From the perspective of these functions, all computation is
 * infinitely fast, and the only effect which advances time is [[IO.sleep]]
 * (or if something external, such as the test harness, calls the [[advance]]
 * method). However, an effect such as `IO(System.currentTimeMillis())` will
 * "see through" the illusion, since the system clock is unaffected by this
 * runtime. This is one reason why it is important to always and exclusively
 * rely on `realTime` and `monotonic`, either directly on `IO` or via the
 * typeclass abstractions.
 *
 * WARNING: ''Never'' use this runtime on programs which use the [[IO#evalOn]]
 * method! The test runtime will detect this situation as an asynchronous
 * deadlock.
 *
 * @see [[cats.effect.unsafe.IORuntime]]
 * @see [[cats.effect.kernel.Clock]]
 * @see [[tickAll]]
 */
final class TestControl private (config: IORuntimeConfig, _seed: Option[String]) {

  private[this] val ctx =
    _seed match {
      case Some(seed) => TestContext(seed)
      case None => TestContext()
    }

  /**
   * An [[IORuntime]] which is controlled by the side-effecting
   * methods on this class.
   *
   * @see [[tickAll]]
   */
  val runtime: IORuntime = IORuntime(
    ctx,
    ctx,
    new Scheduler {
      def sleep(delay: FiniteDuration, task: Runnable): Runnable = {
        val cancel = ctx.schedule(delay, task)
        () => cancel()
      }

      def nowMillis() =
        ctx.now().toMillis

      def monotonicNanos() =
        ctx.now().toNanos
    },
    () => (),
    config
  )

  /**
   * Returns the minimum time which must elapse for a fiber to become
   * eligible for execution. If fibers are currently eligible for
   * execution, the result will be `Duration.Zero`.
   */
  def nextInterval(): FiniteDuration =
    ctx.nextInterval()

  /**
   * Advances the runtime clock by the specified amount (which must
   * be positive). Does not execute any fibers, though may result in
   * some previously-sleeping fibers to become pending and eligible
   * for execution in the next [[tick]].
   */
  def advance(time: FiniteDuration): Unit =
    ctx.advance(time)

  /**
   * A convenience method which advances time by the specified amount
   * and then ticks once. Note that this method is very subtle and
   * will often ''not'' do what you think it should. For example:
   *
   * {{{
   *   // will never print!
   *   val program = IO.sleep(100.millis) *> IO.println("Hello, World!")
   *
   *   val control = TestControl()
   *   program.unsafeRunAndForget()(control.runtime)
   *
   *   control.advanceAndTick(1.second)
   * }}}
   *
   * This is very subtle, but the problem is that time is advanced
   * ''before'' the [[IO.sleep]] even has a chance to get scheduled!
   * This means that when `sleep` is finally submitted to the runtime,
   * it is scheduled for the time offset equal to `1.second + 100.millis`,
   * since time was already advanced `1.second` before it had a chance
   * to submit. Of course, time has only been advanced by `1.second`,
   * thus the `sleep` never completes and the `println` cannot ever run.
   *
   * There are two possible solutions to this problem: either call [[tick]]
   * ''first'' (before calling `advanceAndTick`) to ensure that the `sleep`
   * has a chance to schedule itself, or simply use [[tickAll]] if you
   * do not need to run assertions between time windows.
   *
   * @see [[advance]]
   * @see [[tick]]
   */
  def advanceAndTick(time: FiniteDuration): Unit =
    ctx.advanceAndTick(time)

  /**
   * Executes a single pending fiber and returns immediately. Does not
   * advance time. Returns `false` if no fibers are pending.
   */
  def tickOne(): Boolean =
    ctx.tickOne()

  /**
   * Executes all pending fibers in a random order, repeating on new tasks
   * enqueued by those fibers until all pending fibers have been exhausted.
   * Does not result in the advancement of time.
   *
   * @see [[advance]]
   * @see [[tickAll]]
   */
  def tick(): Unit =
    ctx.tick()

  /**
   * Drives the runtime until all fibers have been executed, then advances
   * time until the next fiber becomes available (if relevant), and repeats
   * until no further fibers are scheduled. Analogous to, though critically
   * not the same as, running an [[IO]]] on a single-threaded production
   * runtime.
   *
   * This function will terminate for `IO`s which deadlock ''asynchronously'',
   * but any program which runs in a loop without fully suspending will cause
   * this function to run indefinitely. Also note that any `IO` which interacts
   * with some external asynchronous scheduler (such as NIO) will be considered
   * deadlocked for the purposes of this runtime.
   *
   * @see [[tick]]
   */
  def tickAll(): Unit =
    ctx.tickAll()

  /**
   * Returns `true` if the runtime has no remaining fibers, sleeping or otherwise,
   * indicating an asynchronous deadlock has occurred. Or rather, ''either'' an
   * asynchronous deadlock, or some interaction with an external asynchronous
   * scheduler (such as another thread pool).
   */
  def isDeadlocked(): Boolean =
    ctx.state.tasks.isEmpty

  /**
   * Produces the base64-encoded seed which governs the random task interleaving
   * during each [[tick]]. This is useful for reproducing test failures which
   * came about due to some unexpected (though clearly plausible) execution order.
   */
  def seed: String = ctx.seed
}

object TestControl {
  def apply(
      config: IORuntimeConfig = IORuntimeConfig(),
      seed: Option[String] = None): TestControl =
    new TestControl(config, seed)
}
