/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats.effect.bifunctor

import cats.effect.Fiber
import cats.effect.IO
import scala.annotation.implicitNotFound

@implicitNotFound("""Cannot find implicit value for Concurrent[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait Concurrent[F[_], E] extends Async[F, E] {
  /**
   * Creates a cancelable `F[A]` instance that executes an
   * asynchronous process on evaluation.
   *
   * This builder accepts a registration function that is
   * being injected with a side-effectful callback, to be called
   * when the asynchronous process is complete with a final result.
   *
   * The registration function is also supposed to return
   * an `IO[Unit]` that captures the logic necessary for
   * canceling the asynchronous process, for as long as it
   * is still active.
   *
   * Example:
   *
   * {{{
   *   import java.util.concurrent.ScheduledExecutorService
   *   import scala.concurrent.duration._
   *
   *   def sleep[F[_]](d: FiniteDuration)
   *     (implicit F: Concurrent[F], ec: ScheduledExecutorService): F[Unit] = {
   *
   *     F.cancelable { cb =>
   *       // Note the callback is pure, so we need to trigger evaluation
   *       val run = new Runnable { def run() = cb(Right(())) }
   *
   *       // Schedules task to run after delay
   *       val future = ec.schedule(run, d.length, d.unit)
   *
   *       // Cancellation logic, suspended in IO
   *       IO(future.cancel(true))
   *     }
   *   }
   * }}}
   */
  def cancelable[A](k: (Either[E, A] => Unit) => IO[Unit]): F[A]

  /**
   * Returns a new `F` that mirrors the source, but that is uninterruptible.
   *
   * This means that the [[Fiber.cancel cancel]] signal has no effect on the
   * result of [[Fiber.join join]] and that the cancelable token returned by
   * [[ConcurrentEffect.runCancelable]] on evaluation will have no effect.
   *
   * This operation is undoing the cancellation mechanism of [[cancelable]],
   * with this equivalence:
   *
   * {{{
   *   F.uncancelable(F.cancelable { cb => f(cb); io }) <-> F.async(f)
   * }}}
   *
   * Sample:
   *
   * {{{
   *   val F = Concurrent[IO]
   *   val timer = Timer[IO]
   *
   *   // Normally Timer#sleep yields cancelable tasks
   *   val tick = F.uncancelable(timer.sleep(10.seconds))
   *
   *   // This prints "Tick!" after 10 seconds, even if we are
   *   // canceling the Fiber after start:
   *   for {
   *     fiber <- F.start(tick)
   *     _ <- fiber.cancel
   *     _ <- fiber.join
   *   } yield {
   *     println("Tick!")
   *   }
   * }}}
   *
   * Cancelable effects are great in race conditions, however sometimes
   * this operation is necessary to ensure that the bind continuation
   * of a task (the following `flatMap` operations) are also evaluated
   * no matter what.
   */
  def uncancelable[A](fa: F[A]): F[A]

  /**
   * Returns a new `F` value that mirrors the source for normal
   * termination, but that triggers the given error on cancellation.
   *
   * This `onCancelRaiseError` operator transforms any task into one
   * that on cancellation will terminate with the given error, thus
   * transforming potentially non-terminating tasks into ones that
   * yield a certain error.
   *
   * {{{
   *   import scala.concurrent.CancellationException
   *
   *   val F = Concurrent[IO]
   *   val timer = Timer[IO]
   *
   *   val error = new CancellationException("Boo!")
   *   val fa = F.onCancelRaiseError(timer.sleep(5.seconds, error))
   *
   *   fa.start.flatMap { fiber =>
   *     fiber.cancel *> fiber.join
   *   }
   * }}}
   *
   * Without "onCancelRaiseError" the [[Timer.sleep sleep]] operation
   * yields a non-terminating task on cancellation. But by applying
   * "onCancelRaiseError", the yielded task above will terminate with
   * the indicated "CancellationException" reference, which we can
   * then also distinguish from other errors thrown in the `F` context.
   *
   * Depending on the implementation, tasks that are canceled can
   * become non-terminating. This operation ensures that when
   * cancellation happens, the resulting task is terminated with an
   * error, such that logic can be scheduled to happen after
   * cancellation:
   *
   * {{{
   *   import scala.concurrent.CancellationException
   *   val wasCanceled = new CancellationException()
   *
   *   F.onCancelRaiseError(fa, wasCanceled).attempt.flatMap {
   *     case Right(a) =>
   *       F.delay(println(s"Success: \$a"))
   *     case Left(`wasCanceled`) =>
   *       F.delay(println("Was canceled!"))
   *     case Left(error) =>
   *       F.delay(println(s"Terminated in error: \$error"))
   *   }
   * }}}
   *
   * This technique is how a "bracket" operation can be implemented.
   *
   * Besides sending the cancellation signal, this operation also cuts
   * the connection between the producer and the consumer. Example:
   *
   * {{{
   *   val F = Concurrent[IO]
   *   val timer = Timer[IO]
   *
   *   // This task is uninterruptible ;-)
   *   val tick = F.uncancelable(
   *     for {
   *       _ <- timer.sleep(5.seconds)
   *       _ <- IO(println("Tick!"))
   *     } yield ())
   *
   *   // Builds an value that triggers an exception on cancellation
   *   val loud = F.onCancelRaiseError(tick, new CancellationException)
   * }}}
   *
   * In this example the `loud` reference will be completed with a
   * "CancellationException", as indicated via "onCancelRaiseError".
   * The logic of the source won't get canceled, because we've
   * embedded it all in [[uncancelable]]. But its bind continuation is
   * not allowed to continue after that, its final result not being
   * allowed to be signaled.
   *
   * Therefore this also transforms [[uncancelable]] values into ones
   * that can be canceled. The logic of the source, its run-loop
   * might not be interruptible, however `cancel` on a value on which
   * `onCancelRaiseError` was applied will cut the connection from
   * the producer, the consumer receiving the indicated error instead.
   */
  def onCancelRaiseError[A](fa: F[A], e: E): F[A]

  /**
   * Start concurrent execution of the source suspended in
   * the `F` context.
   *
   * Returns a [[Fiber]] that can be used to either join or cancel
   * the running computation, being similar in spirit (but not
   * in implementation) to starting a thread.
   */
  def start[A](fa: F[A]): F[Fiber[F, A]]

  /**
   * Run two tasks concurrently, creating a race between them and returns a
   * pair containing both the winner's successful value and the loser
   * represented as a still-unfinished fiber.
   *
   * If the first task completes in error, then the result will
   * complete in error, the other task being canceled.
   *
   * On usage the user has the option of canceling the losing task,
   * this being equivalent with plain [[race]]:
   *
   * {{{
   *   val ioA: IO[A] = ???
   *   val ioB: IO[B] = ???
   *
   *   Concurrent[IO].racePair(ioA, ioB).flatMap {
   *     case Left((a, fiberB)) =>
   *       fiberB.cancel.map(_ => a)
   *     case Right((fiberA, b)) =>
   *       fiberA.cancel.map(_ => b)
   *   }
   * }}}
   *
   * See [[race]] for a simpler version that cancels the loser
   * immediately.
   */
  def racePair[A,B](fa: F[A], fb: F[B]): F[Either[(A, Fiber[F, B]), (Fiber[F, A], B)]]

  /**
   * Run two tasks concurrently and return the first to finish,
   * either in success or error. The loser of the race is canceled.
   *
   * The two tasks are potentially executed in parallel, the winner
   * being the first that signals a result.
   *
   * As an example, this would be the implementation of a "timeout"
   * operation:
   *
   * {{{
   *   import cats.effect._
   *   import scala.concurrent.duration._
   *
   *   def timeoutTo[F[_], A](fa: F[A], after: FiniteDuration, fallback: F[A])
   *     (implicit F: Concurrent[F], timer: Timer[F]): F[A] = {
   *
   *      F.race(fa, timer.sleep(after)).flatMap {
   *        case Left((a, _)) => F.pure(a)
   *        case Right((_, _)) => fallback
   *      }
   *   }
   *
   *   def timeout[F[_], A](fa: F[A], after: FiniteDuration)
   *     (implicit F: Concurrent[F], timer: Timer[F]): F[A] = {
   *
   *      timeoutTo(fa, after,
   *        F.raiseError(new TimeoutException(after.toString)))
   *   }
   * }}}
   *
   * Also see [[racePair]] for a version that does not cancel
   * the loser automatically on successful results.
   */
  def race[A, B](fa: F[A], fb: F[B]): F[Either[A, B]] =
    flatMap(racePair(fa, fb)) {
      case Left((a, fiberB)) => map(fiberB.cancel)(_ => Left(a))
      case Right((fiberA, b)) => map(fiberA.cancel)(_ => Right(b))
    }
}
