/*
 * Copyright 2020-2024 Typelevel
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

import cats.{~>, Id}
import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}

import scala.concurrent.CancellationException
import scala.concurrent.duration.{Duration, FiniteDuration}

import java.util.concurrent.atomic.AtomicReference

/**
 * Implements a fully functional single-threaded runtime for a [[cats.effect.IO]] program. When
 * using this control system, `IO` programs will be executed on a single JVM thread, ''similar''
 * to how they would behave if the production runtime were configured to use a single worker
 * thread regardless of underlying physical thread count. The results of the underlying `IO`
 * will be produced by the [[results]] effect when ready, but nothing will actually evaluate
 * until one of the ''tick'' effects on this class are sequenced. If the desired behavior is to
 * simply run the `IO` fully to completion within the mock environment, respecting monotonic
 * time, then [[tickAll]] is likely the desired effect (or, alternatively,
 * [[TestControl.executeEmbed]]).
 *
 * In other words, `TestControl` is sort of like a "handle" to the runtime internals within the
 * context of a specific `IO`'s execution. It makes it possible for users to manipulate and
 * observe the execution of the `IO` under test from an external vantage point. It is important
 * to understand that the ''outer'' `IO`s (e.g. those returned by the [[tick]] or [[results]]
 * methods) are ''not'' running under the test control environment, and instead they are meant
 * to be run by some outer runtime. Interactions between the outer runtime and the inner runtime
 * (potentially via mechanisms like [[cats.effect.std.Queue]] or
 * [[cats.effect.kernel.Deferred]]) are quite tricky and should only be done with extreme care.
 * The likely outcome in such scenarios is that the `TestControl` runtime will detect the inner
 * `IO` as being deadlocked whenever it is actually waiting on the external runtime. This could
 * result in strange effects such as [[tickAll]] or `executeEmbed` terminating early. Do not
 * construct such scenarios unless you're very confident you understand the implications of what
 * you're doing.
 *
 * Where things ''differ'' from a single-threaded production runtime is in two critical areas.
 *
 * First, whenever multiple fibers are outstanding and ready to be resumed, the `TestControl`
 * runtime will ''randomly'' choose between them, rather than taking them in a first-in,
 * first-out fashion as the default production runtime will. This semantic is intended to
 * simulate different scheduling interleavings, ensuring that race conditions are not
 * accidentally masked by deterministic execution order.
 *
 * Second, within the context of the `TestControl`, ''time'' is very carefully and artificially
 * controlled. In a sense, this runtime behaves as if it is executing on a single CPU which
 * performs all operations infinitely fast. Any fibers which are immediately available for
 * execution will be executed until no further fibers are available to run (assuming the use of
 * `tickAll`). Through this entire process, the current clock (which is exposed to the program
 * via [[IO.realTime]] and [[IO.monotonic]]) will remain fixed at the very beginning, meaning
 * that no time is considered to have elapsed as a consequence of ''compute''.
 *
 * Note that the above means that it is relatively easy to create a deadlock on this runtime
 * with a program which would not deadlock on either the JVM or JavaScript:
 *
 * {{{
 *   // do not do this!
 *   IO.cede.foreverM.timeout(10.millis)
 * }}}
 *
 * The above program spawns a fiber which yields forever, setting a timeout for 10 milliseconds
 * which is ''intended'' to bring the loop to a halt. However, because the immediate task queue
 * will never be empty, the test runtime will never advance time, meaning that the 10
 * milliseconds will never elapse and the timeout will not be hit. This will manifest as the
 * [[tick]] and [[tickAll]] effects simply running forever and not returning if called.
 * [[tickOne]] is safe to call on the above program, but it will always produce `true`.
 *
 * In order to advance time, you must use the [[advance]] effect to move the clock forward by a
 * specified offset (which must be greater than 0). If you use the `tickAll` effect, the clock
 * will be automatically advanced by the minimum amount necessary to reach the next pending
 * task. For example, if the program contains an [[IO.sleep(delay*]] for `500.millis`, and there
 * are no shorter sleeps, then time will need to be advanced by 500 milliseconds in order to
 * make that fiber eligible for execution.
 *
 * At this point, the process repeats until all tasks are exhausted. If the program has reached
 * a concluding value or exception, then it will be produced from the `unsafeRun` method which
 * scheduled the `IO` on the runtime (pro tip: do ''not'' use `unsafeRunSync` with this runtime,
 * since it will always result in immediate deadlock). If the program does ''not'' produce a
 * result but also has no further work to perform (such as a program like [[IO.never]]), then
 * `tickAll` will return but no result will have been produced by the `unsafeRun`. If this
 * happens, [[isDeadlocked]] will return `true` and the program is in a "hung" state. This same
 * situation on the production runtime would have manifested as an asynchronous deadlock.
 *
 * You should ''never'' use this runtime in a production code path. It is strictly meant for
 * testing purposes, particularly testing programs that involve time functions and
 * [[IO.sleep(delay*]].
 *
 * Due to the semantics of this runtime, time will behave entirely consistently with a plausible
 * production execution environment provided that you ''never'' observe time via side-effects,
 * and exclusively through the [[IO.realTime]], [[IO.monotonic]], and [[IO.sleep(delay*]]
 * functions (and other functions built on top of these). From the perspective of these
 * functions, all computation is infinitely fast, and the only effect which advances time is
 * [[IO.sleep(delay*]] (or if something external, such as the test harness, sequences the
 * [[advance]] effect). However, an effect such as `IO(System.currentTimeMillis())` will "see
 * through" the illusion, since the system clock is unaffected by this runtime. This is one
 * reason why it is important to always and exclusively rely on `realTime` and `monotonic`,
 * either directly on `IO` or via the typeclass abstractions.
 *
 * WARNING: ''Never'' use this runtime on programs which use the [[IO#evalOn]] method! The test
 * runtime will detect this situation as an asynchronous deadlock.
 *
 * @see
 *   [[cats.effect.kernel.Clock]]
 * @see
 *   [[tickAll]]
 */
final class TestControl[A] private (
    ctx: TestContext,
    _results: AtomicReference[Option[Outcome[Id, Throwable, A]]]) {

  val results: IO[Option[Outcome[Id, Throwable, A]]] = IO(_results.get)

  /**
   * Produces the minimum time which must elapse for a fiber to become eligible for execution.
   * If fibers are currently eligible for execution, or if the program is entirely deadlocked,
   * the result will be `Duration.Zero`.
   */
  val nextInterval: IO[FiniteDuration] =
    IO(ctx.nextInterval())

  /**
   * Advances the runtime clock by the specified amount (which must be positive). Does not
   * execute any fibers, though may result in some previously-sleeping fibers to become pending
   * and eligible for execution in the next [[tick]].
   */
  def advance(time: FiniteDuration): IO[Unit] =
    IO(ctx.advance(time))

  /**
   * A convenience effect which advances time by the specified amount and then ticks once. Note
   * that this method is very subtle and will often ''not'' do what you think it should. For
   * example:
   *
   * {{{
   *   // will never print!
   *   val program = IO.sleep(100.millis) *> IO.println("Hello, World!")
   *
   *   TestControl.execute(program).flatMap(_.advanceAndTick(1.second))
   * }}}
   *
   * This is very subtle, but the problem is that time is advanced ''before'' the
   * [[IO.sleep(delay*]] even has a chance to get scheduled! This means that when `sleep` is
   * finally submitted to the runtime, it is scheduled for the time offset equal to `1.second +
   * 100.millis`, since time was already advanced `1.second` before it had a chance to submit.
   * Of course, time has only been advanced by `1.second`, thus the `sleep` never completes and
   * the `println` cannot ever run.
   *
   * There are two possible solutions to this problem: either sequence [[tick]] ''first''
   * (before sequencing `advanceAndTick`) to ensure that the `sleep` has a chance to schedule
   * itself, or simply use [[tickAll]] if you do not need to run assertions between time
   * windows.
   *
   * In most cases, [[tickFor]] will provide a more intuitive execution semantic.
   *
   * @see
   *   [[advance]]
   * @see
   *   [[tick]]
   * @see
   *   [[tickFor]]
   */
  def advanceAndTick(time: FiniteDuration): IO[Unit] =
    IO(ctx.advanceAndTick(time))

  /**
   * Executes a single pending fiber and returns immediately. Does not advance time. Produces
   * `false` if no fibers are pending.
   */
  val tickOne: IO[Boolean] =
    IO(ctx.tickOne())

  /**
   * Executes all pending fibers in a random order, repeating on new tasks enqueued by those
   * fibers until all pending fibers have been exhausted. Does not result in the advancement of
   * time.
   *
   * @see
   *   [[advance]]
   * @see
   *   [[tickAll]]
   */
  val tick: IO[Unit] =
    IO(ctx.tick())

  /**
   * Drives the runtime until all fibers have been executed, then advances time until the next
   * fiber becomes available (if relevant), and repeats until no further fibers are scheduled.
   * Analogous to, though critically not the same as, running an [[IO]] on a single-threaded
   * production runtime.
   *
   * This function will terminate for `IO`s which deadlock ''asynchronously'', but any program
   * which runs in a loop without fully suspending will cause this function to run indefinitely.
   * Also note that any `IO` which interacts with some external asynchronous scheduler (such as
   * NIO) will be considered deadlocked for the purposes of this runtime.
   *
   * @see
   *   [[tick]]
   */
  val tickAll: IO[Unit] =
    IO(ctx.tickAll())

  /**
   * Drives the runtime incrementally forward until all fibers have been executed, or until the
   * specified `time` has elapsed. The semantics of this function are very distinct from
   * [[advance]] in that the runtime will tick for the minimum time necessary to reach the next
   * batch of tasks within each interval, and then continue ticking as long as necessary to
   * cumulatively reach the time limit (or the end of the program). This behavior can be seen in
   * programs such as the following:
   *
   * {{{
   *   val tick = IO.sleep(1.second) *> IO.realTime
   *
   *   TestControl.execute((tick, tick).tupled) flatMap { control =>
   *     for {
   *       _ <- control.tickFor(1.second + 500.millis)
   *       _ <- control.tickAll
   *
   *       r <- control.results
   *       _ <- IO(assert(r == Some(Outcome.succeeded(1.second, 2.seconds))))
   *     } yield ()
   *   }
   * }}}
   *
   * Notably, the first component of the results tuple here is `1.second`, meaning that the
   * first `IO.realTime` evaluated after the clock had ''only'' advanced by `1.second`. This is
   * in contrast to what would have happened with `control.advanceAndTick(1.second +
   * 500.millis)`, which would have caused the first `realTime` to produce `2500.millis` as a
   * result, rather than the correct answer of `1.second`. In other words, [[advanceAndTick]] is
   * maximally aggressive on time advancement, while `tickFor` is maximally conservative and
   * only ticks as much as necessary each time.
   *
   * @see
   *   [[tick]]
   * @see
   *   [[advance]]
   */
  def tickFor(time: FiniteDuration): IO[Unit] =
    tick *> nextInterval flatMap { next =>
      val step = time.min(next)
      val remaining = time - step

      if (step <= Duration.Zero) {
        IO.unit
      } else {
        advance(step) *> {
          if (remaining <= Duration.Zero)
            tick
          else
            tickFor(remaining)
        }
      }
    }

  /**
   * Produces `true` if the runtime has no remaining fibers, sleeping or otherwise, indicating
   * an asynchronous deadlock has occurred. Or rather, ''either'' an asynchronous deadlock, or
   * some interaction with an external asynchronous scheduler (such as another thread pool).
   */
  val isDeadlocked: IO[Boolean] =
    IO(!_results.get().isDefined && ctx.state.tasks.isEmpty)

  /**
   * Returns the base64-encoded seed which governs the random task interleaving during each
   * [[tick]]. This is useful for reproducing test failures which came about due to some
   * unexpected (though clearly plausible) execution order.
   */
  def seed: String = ctx.seed
}

object TestControl {

  /**
   * Executes a given [[IO]] under fully mocked runtime control. Produces a `TestControl` which
   * can be used to manipulate the mocked runtime and retrieve the results. Note that the outer
   * `IO` (and the `IO`s produced by the `TestControl`) do ''not'' evaluate under mocked runtime
   * control and must be evaluated by some external harness, usually some test framework
   * integration.
   *
   * A simple example (returns an `IO` which must, itself, be run) using MUnit assertion syntax:
   *
   * {{{
   *   val program = for {
   *     first <- IO.realTime   // IO.monotonic also works
   *     _ <- IO.println("it is currently " + first)
   *
   *     _ <- IO.sleep(100.milis)
   *     second <- IO.realTime
   *     _ <- IO.println("we slept and now it is " + second)
   *
   *     _ <- IO.sleep(1.hour).timeout(1.minute)
   *     third <- IO.realTime
   *     _ <- IO.println("we slept a second time and now it is " + third)
   *   } yield ()
   *
   *   TestControl.execute(program) flatMap { control =>
   *     for {
   *       first <- control.results
   *       _ <- IO(assert(first == None))   // we haven't finished yet
   *
   *       _ <- control.tick
   *       // at this point, the "it is currently ..." line will have printed
   *
   *       next1 <- control.nextInterval
   *       _ <- IO(assert(next1 == 100.millis))
   *
   *       _ <- control.advance(100.millis)
   *       // nothing has happened yet!
   *       _ <- control.tick
   *       // now the "we slept and now it is ..." line will have printed
   *
   *       second <- control.results
   *       _ <- IO(assert(second == None))  // we're still not done yet
   *
   *       next2 <- control.nextInterval
   *       _ <- IO(assert(next2 == 1.minute))   // we need to wait one minute for our next task, since we will hit the timeout
   *
   *       _ <- control.advance(15.seconds)
   *       _ <- control.tick
   *       // nothing happens!
   *
   *       next3 <- control.nextInterval
   *       _ <- IO(assert(next3 == 45.seconds))   // haven't gone far enough to hit the timeout
   *
   *       _ <- control.advanceAndTick(45.seconds)
   *       // at this point, nothing will print because we hit the timeout exception!
   *
   *       third <- control.results
   *
   *       _ <- IO {
   *         assert(third.isDefined)
   *         assert(third.get.isError)   // an exception, not a value!
   *         assert(third.get.fold(false, _.isInstanceOf[TimeoutException], _ => false))
   *       }
   *     } yield ()
   *   }
   * }}}
   *
   * The above will run to completion within milliseconds.
   *
   * If your assertions are entirely intrinsic (within the program) and the test is such that
   * time should advance in an automatic fashion, [[executeEmbed]] may be a more convenient
   * option.
   */
  def execute[A](
      program: IO[A],
      config: IORuntimeConfig = IORuntimeConfig(),
      seed: Option[String] = None): IO[TestControl[A]] =
    IO {
      val ctx = seed match {
        case Some(seed) => TestContext(seed)
        case None => TestContext()
      }

      val runtime: IORuntime = IORuntime(
        ctx,
        ctx.deriveBlocking(),
        new Scheduler {
          def sleep(delay: FiniteDuration, task: Runnable): Runnable = {
            val cancel = ctx.schedule(delay, task)
            () => cancel()
          }

          def nowMillis() =
            ctx.now().toMillis

          override def nowMicros(): Long =
            ctx.now().toMicros

          def monotonicNanos() =
            ctx.now().toNanos
        },
        () => (),
        config
      )

      val results = new AtomicReference[Option[Outcome[Id, Throwable, A]]](None)
      program.unsafeRunAsyncOutcome(oc => results.set(Some(oc)))(runtime)
      new TestControl(ctx, results)
    }

  /**
   * Executes an [[IO]] under fully mocked runtime control, returning the final results. This is
   * very similar to calling `unsafeRunSync` on the program and wrapping it in an `IO`, except
   * that the scheduler will use a mocked and quantized notion of time, all while executing on a
   * singleton worker thread. This can cause some programs to deadlock which would otherwise
   * complete normally, but it also allows programs which involve [[IO.sleep(delay*]] s of any
   * length to complete almost instantly with correct semantics.
   *
   * Note that any program which involves an [[IO.async]] that waits for some external thread
   * (including [[IO.evalOn]]) will be detected as a deadlock and will result in the
   * `executeEmbed` effect immediately producing a [[NonTerminationException]].
   *
   * @return
   *   An `IO` which runs the given program under a mocked runtime, producing the result or an
   *   error if the program runs to completion. If the program is canceled, a
   *   [[scala.concurrent.CancellationException]] will be raised within the `IO`. If the program
   *   fails to terminate with either a result or an error, a [[NonTerminationException]] will
   *   be raised.
   */
  def executeEmbed[A](
      program: IO[A],
      config: IORuntimeConfig = IORuntimeConfig(),
      seed: Option[String] = None): IO[A] =
    execute(program, config = config, seed = seed) flatMap { c =>
      val nt = new (Id ~> IO) { def apply[E](e: E) = IO.pure(e) }

      val onCancel = IO.defer(IO.raiseError(new CancellationException()))
      val onNever = IO.raiseError(new NonTerminationException())
      val embedded = c.results.flatMap(_.map(_.mapK(nt).embed(onCancel)).getOrElse(onNever))

      c.tickAll *> embedded
    }

  final class NonTerminationException
      extends RuntimeException(
        "Program under test failed produce a result (either a value or an error) and has no further " +
          "actions to take, likely indicating an asynchronous deadlock. This may also indicate some " +
          "interaction with an external thread, potentially via IO.async or IO#evalOn. If this is the " +
          "case, then it is likely you cannot use TestControl to correctly evaluate this program, and " +
          "you should either use the production IORuntime (ideally via some integration with your " +
          "testing framework), or attempt to refactor the program into smaller, more testable components.")
}
