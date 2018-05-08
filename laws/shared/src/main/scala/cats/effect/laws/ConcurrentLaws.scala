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
package laws

import cats.effect.laws.util.Pledge
import cats.laws._
import cats.syntax.all._

trait ConcurrentLaws[F[_]] extends AsyncLaws[F] with UConcurrentLaws[F] {
  implicit def F: Concurrent[F]

  def cancelOnBracketReleases[A, B](a: A, f: (A, A) => B) = {
    val received = for {
      // A promise that waits for `use` to get executed
      startLatch <- Pledge[F, A]
      // A promise that waits for `release` to be executed
      exitLatch <- Pledge[F, A]
      // What we're actually testing
      bracketed = F.bracketCase(F.pure(a))(a => startLatch.complete[F](a) *> F.never[A]) {
        case (r, ExitCase.Canceled(_)) => exitLatch.complete[F](r)
        case (_, _) => F.unit
      }
      // Forked execution, allowing us to cancel it later
      fiber <- F.start(bracketed)
      // Waits for the `use` action to execute
      waitStart <- startLatch.await[F]
      // Triggers cancelation
      _ <- fiber.cancel
      // Observes cancelation via bracket's `release`
      waitExit <- exitLatch.await[F]
    } yield f(waitStart, waitExit)

    received <-> F.pure(f(a, a))
  }

  def asyncCancelableCoherence[A](r: Either[Throwable, A]) = {
    F.async[A](cb => cb(r)) <-> F.cancelable[A] { cb => cb(r); IO.unit }
  }

  def asyncCancelableReceivesCancelSignal[A](a: A) = {
    val lh = Pledge[F, A].flatMap { effect =>
      val async = F.cancelable[Unit](_ => effect.complete[IO](a))
      F.start(async).flatMap(_.cancel) *> effect.await
    }
    lh <-> F.pure(a)
  }

  def onCancelRaiseErrorMirrorsSource[A](fa: F[A], e: Throwable) = {
    F.onCancelRaiseError(fa, e) <-> fa
  }

  def onCancelRaiseErrorTerminatesOnCancel[A](e: Throwable) = {
    val never = F.onCancelRaiseError(F.never[A], e)
    val received =
      for {
        fiber <- F.start(never)
        _ <- fiber.cancel
        r <- fiber.join
      } yield r

    received <-> F.raiseError(e)
  }

  def onCancelRaiseErrorCanCancelSource[A](a: A, e: Throwable) = {
    val lh = Pledge[F, A].flatMap { effect =>
      val async = F.cancelable[Unit](_ => effect.complete[IO](a))
      F.start(F.onCancelRaiseError(async, e))
        .flatMap(_.cancel) *> effect.await
    }
    lh <-> F.pure(a)
  }


  //TODO Move this to UConcurrentLaws
  def racePairCancelsLoser[A, B](r: Either[Throwable, A], leftWinner: Boolean, b: B) = {
    val received: F[B] = Pledge[F, B].flatMap { effect =>
      val winner = F.async[A](_(r))
      val loser = F.cancelable[A](_ => effect.complete[IO](b))
      val race =
        if (leftWinner) F.racePair(winner, loser)
        else F.racePair(loser, winner)

      F.attempt(race).flatMap {
        case Right(Left((_, fiber))) =>
          fiber.cancel *> effect.await[F]
        case Right(Right((fiber, _))) =>
          fiber.cancel *> effect.await[F]
        case Left(_) =>
          effect.await[F]
      }
    }
    received <-> F.pure(b)
  }
}

object ConcurrentLaws {
  def apply[F[_]](implicit F0: Concurrent[F]): ConcurrentLaws[F] = new ConcurrentLaws[F] {
    val F = F0
  }
}
