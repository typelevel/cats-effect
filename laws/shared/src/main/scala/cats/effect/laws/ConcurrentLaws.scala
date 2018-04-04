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
import scala.Predef.{identity => id}

trait ConcurrentLaws[F[_]] extends AsyncLaws[F] {
  implicit def F: Concurrent[F]

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

  def startJoinIsIdentity[A](fa: F[A]) =
    F.start(fa).flatMap(_.join) <-> fa

  def joinIsIdempotent[A](a: A) = {
    val lh = Pledge[F, A].flatMap { p =>
      // N.B. doing effect.complete twice triggers error
      F.start(p.complete(a)).flatMap(t => t.join *> t.join) *> p.await
    }
    lh <-> F.pure(a)
  }

  def startCancelIsUnit[A](fa: F[A]) = {
    F.start(fa).flatMap(_.cancel) <-> F.unit
  }

  def uncancelableMirrorsSource[A](fa: F[A]) = {
    F.uncancelable(fa) <-> fa
  }

  def uncancelablePreventsCancelation[A](a: A) = {
    val lh = Pledge[F, A].flatMap { p =>
      val async = F.cancelable[Unit](_ => p.complete[IO](a))
      F.start(F.uncancelable(async)).flatMap(_.cancel) *> p.await
    }
    // Non-terminating
    lh <-> F.async(_ => ())
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

  def raceMirrorsLeftWinner[A](fa: F[A], default: A) = {
    F.race(fa, F.never[A]).map(_.left.getOrElse(default)) <-> fa
  }

  def raceMirrorsRightWinner[A](fa: F[A], default: A) = {
    F.race(F.never[A], fa).map(_.right.getOrElse(default)) <-> fa
  }

  def raceCancelsLoser[A, B](r: Either[Throwable, A], leftWinner: Boolean, b: B) = {
    val received = Pledge[F, B].flatMap { effect =>
      val winner = F.async[A](_(r))
      val loser = F.cancelable[A](_ => effect.complete[IO](b))
      val race =
        if (leftWinner) F.race(winner, loser)
        else F.race(loser, winner)

      F.attempt(race) *> effect.await
    }
    received <-> F.pure(b)
  }

  def raceCancelsBoth[A, B, C](a: A, b: B, f: (A, B) => C) = {
    val fc = for {
      pa <- Pledge[F, A]
      loserA = F.cancelable[A](_ => pa.complete[IO](a))
      pb <- Pledge[F, B]
      loserB = F.cancelable[B](_ => pb.complete[IO](b))
      race <- F.start(F.race(loserA, loserB))
      _ <- race.cancel
      a <- pa.await[F]
      b <- pb.await[F]
    } yield f(a, b)

    fc <-> F.pure(f(a, b))
  }

  def racePairMirrorsLeftWinner[A](fa: F[A]) = {
    val never = F.never[A]
    val received =
      F.racePair(fa, never).flatMap {
        case Left((a, fiberB)) =>
          fiberB.cancel.map(_ => a)
        case Right(_) =>
          F.raiseError[A](new IllegalStateException("right"))
      }
    received <-> F.race(fa, never).map(_.fold(id, id))
  }

  def racePairMirrorsRightWinner[B](fb: F[B]) = {
    val never = F.never[B]
    val received =
      F.racePair(never, fb).flatMap {
        case Right((fiberA, b)) =>
          fiberA.cancel.map(_ => b)
        case Left(_) =>
          F.raiseError[B](new IllegalStateException("left"))
      }
    received <-> F.race(never, fb).map(_.fold(id, id))
  }

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

  def racePairCanJoinLeft[A](a: A) = {
    val lh = Pledge[F, A].flatMap { fa =>
      F.racePair(fa.await[F], F.unit).flatMap {
        case Left((l, _)) => F.pure(l)
        case Right((fiberL, _)) =>
          fa.complete[F](a) *> fiberL.join
      }
    }
    lh <-> F.pure(a)
  }

  def racePairCanJoinRight[A](a: A) = {
    val lh = Pledge[F, A].flatMap { fa =>
      F.racePair(F.unit, fa.await[F]).flatMap {
        case Left((_, fiberR)) =>
          fa.complete[F](a) *> fiberR.join
        case Right((_, r)) =>
          F.pure(r)
      }
    }
    lh <-> F.pure(a)
  }

  def racePairCancelsBoth[A, B, C](a: A, b: B, f: (A, B) => C) = {
    val fc = for {
      pa <- Pledge[F, A]
      loserA = F.cancelable[A](_ => pa.complete[IO](a))
      pb <- Pledge[F, B]
      loserB = F.cancelable[B](_ => pb.complete[IO](b))
      race <- F.start(F.racePair(loserA, loserB))
      _ <- race.cancel
      a <- pa.await[F]
      b <- pb.await[F]
    } yield f(a, b)

    fc <-> F.pure(f(a, b))
  }
}

object ConcurrentLaws {
  def apply[F[_]](implicit F0: Concurrent[F]): ConcurrentLaws[F] = new ConcurrentLaws[F] {
    val F = F0
  }
}
