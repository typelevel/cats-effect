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

package cats.effect

import simulacrum._

import scala.util.Either

@typeclass
trait UConcurrent[F[_]] extends UAsync[F] {
  def cancelableCatch[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): F[Either[Throwable, A]]

  /**
   * Returns a new `F` that mirrors the source, but that is uninterruptible.
   *
   * This means that the [[Fiber.cancel cancel]] signal has no effect on the
   * result of [[Fiber.join join]] and that the cancelable token returned by
   * [[ConcurrentEffect.runCancelable]] on evaluation will have no effect.
   *
   * This operation is undoing the cancelation mechanism of [[cancelable]],
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
   *   // cancelling the Fiber after start:
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
   * complete in error, the other task being cancelled.
   *
   * On usage the user has the option of cancelling the losing task,
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
   * either in success or error. The loser of the race is cancelled.
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
