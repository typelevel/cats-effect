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

import cats.effect.concurrent.{Deferred, Semaphore}
import cats.laws._
import cats.syntax.all._
import scala.Predef.{identity => id}

trait ConcurrentLaws[F[_]] extends AsyncLaws[F] {
  implicit def F: Concurrent[F]

  def cancelOnBracketReleases[A, B](a: A, f: (A, A) => B) = {
    val received = for {
      // A deferred that waits for `use` to get executed
      startLatch <- Deferred[F, A]
      // A deferred that waits for `release` to be executed
      exitLatch <- Deferred[F, A]
      // What we're actually testing
      bracketed = F.bracketCase(F.pure(a))(a => startLatch.complete(a) *> F.never[A]) {
        case (r, ExitCase.Canceled(_)) => exitLatch.complete(r)
        case (_, _) => F.unit
      }
      // Forked execution, allowing us to cancel it later
      fiber <- F.start(bracketed)
      // Waits for the `use` action to execute
      waitStart <- startLatch.get
      // Triggers cancellation
      _ <- fiber.cancel
      // Observes cancellation via bracket's `release`
      waitExit <- exitLatch.get
    } yield f(waitStart, waitExit)

    received <-> F.pure(f(a, a))
  }

  def asyncCancelableCoherence[A](r: Either[Throwable, A]) = {
    F.async[A](cb => cb(r)) <-> F.cancelable[A] { cb => cb(r); IO.unit }
  }

  def asyncCancelableReceivesCancelSignal[A](a: A) = {
    val lh = F.liftIO(Deferred[IO, A]).flatMap { effect =>
      val async = F.cancelable[Unit](_ => effect.complete(a))
      F.start(async).flatMap(_.cancel) *> F.liftIO(effect.get)
    }
    lh <-> F.pure(a)
  }

  def startJoinIsIdentity[A](fa: F[A]) =
    F.start(fa).flatMap(_.join) <-> fa

  def joinIsIdempotent[A](a: A) = {
    val lh = Deferred[F, A].flatMap { p =>
      // N.B. doing effect.complete twice triggers error
      F.start(p.complete(a)).flatMap(t => t.join *> t.join) *> p.get
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
    val lh = F.liftIO(Deferred[IO, A]).flatMap { p =>
      val async = F.cancelable[Unit](_ => p.complete(a))
      F.start(F.uncancelable(async)).flatMap(_.cancel) *> F.liftIO(p.get)
    }
    // Non-terminating
    lh <-> F.async(_ => ())
  }

  def onCancelRaiseErrorMirrorsSource[A](fa: F[A], e: Throwable) = {
    F.onCancelRaiseError(fa, e) <-> fa
  }

  def onCancelRaiseErrorTerminatesOnCancel[A](e: Throwable) = {
    val received =
      for {
        s <- Semaphore[F](0L)
        never = F.onCancelRaiseError(F.bracket(s.release)(_ => F.never[A])(_ => F.unit), e)
        fiber <- F.start(never)
        _ <- s.acquire *> fiber.cancel
        r <- fiber.join
      } yield r

    received <-> F.raiseError(e)
  }

  def onCancelRaiseErrorCanCancelSource[A](a: A, e: Throwable) = {
    val lh = F.liftIO(Deferred[IO, A]).flatMap { effect =>
      val async = F.cancelable[Unit](_ => effect.complete(a))
      F.start(F.onCancelRaiseError(async, e))
        .flatMap(_.cancel) *> F.liftIO(effect.get)
    }
    lh <-> F.pure(a)
  }

  def onCancelRaiseErrorResetsCancellationFlag[A](a: A, e: Throwable) = {
    val task = F.onCancelRaiseError(F.never[A], e)
    val recovered = F.recoverWith(task) {
      case `e` => F.liftIO(IO.cancelBoundary *> IO(a))
    }
    F.flatMap(F.start(recovered))(f => f.cancel *> f.join) <->
      F.liftIO(IO(a))
  }

  def raceMirrorsLeftWinner[A](fa: F[A], default: A) = {
    F.race(fa, F.never[A]).map(_.left.getOrElse(default)) <-> fa
  }

  def raceMirrorsRightWinner[A](fa: F[A], default: A) = {
    F.race(F.never[A], fa).map(_.right.getOrElse(default)) <-> fa
  }

  def raceCancelsLoser[A, B](r: Either[Throwable, A], leftWinner: Boolean, b: B) = {
    val received = for {
      s <- Semaphore[F](0L)
      effect <- Deferred[F, B]
      winner = s.acquire *> F.async[A](_(r))
      loser = F.bracket(s.release)(_ => F.never[A])(_ => effect.complete(b))
      race =
        if (leftWinner) F.race(winner, loser)
        else F.race(loser, winner)

      b <- F.attempt(race) *> effect.get
    } yield b
    received <-> F.pure(b)
  }

  def raceCancelsBoth[A, B, C](a: A, b: B, f: (A, B) => C) = {
    val fc = for {
      s <- Semaphore[F](0L)
      pa <- Deferred[F, A]
      loserA = F.bracket(s.release)(_ => F.never[A])(_ => pa.complete(a))
      pb <- Deferred[F, B]
      loserB = F.bracket(s.release)(_ => F.never[B])(_ => pb.complete(b))
      race <- F.start(F.race(loserA, loserB))
      _ <- s.acquireN(2L) *> race.cancel
      a <- pa.get
      b <- pb.get
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
    val received: F[B] = for {
      s <- Semaphore[F](0L)
      effect <- Deferred[F, B]
      winner = s.acquire *> F.async[A](_(r))
      loser = F.bracket(s.release)(_ => F.never[A])(_ => effect.complete(b))
      race =
        if (leftWinner) F.racePair(winner, loser)
        else F.racePair(loser, winner)

      b <- F.attempt(race).flatMap {
        case Right(Left((_, fiber))) =>
          fiber.cancel *> effect.get
        case Right(Right((fiber, _))) =>
          fiber.cancel *> effect.get
        case Left(_) =>
          effect.get
      }
    } yield b
    received <-> F.pure(b)
  }

  def racePairCanJoinLeft[A](a: A) = {
    val lh = Deferred[F, A].flatMap { fa =>
      F.racePair(fa.get, F.unit).flatMap {
        case Left((l, _)) => F.pure(l)
        case Right((fiberL, _)) =>
          fa.complete(a) *> fiberL.join
      }
    }
    lh <-> F.pure(a)
  }

  def racePairCanJoinRight[A](a: A) = {
    val lh = Deferred[F, A].flatMap { fa =>
      F.racePair(F.unit, fa.get).flatMap {
        case Left((_, fiberR)) =>
          fa.complete(a) *> fiberR.join
        case Right((_, r)) =>
          F.pure(r)
      }
    }
    lh <-> F.pure(a)
  }

  def racePairCancelsBoth[A, B, C](a: A, b: B, f: (A, B) => C) = {
    val fc = for {
      s <- Semaphore[F](0L)
      pa <- Deferred[F, A]
      loserA = F.bracket(s.release)(_ => F.never[A])(_ => pa.complete(a))
      pb <- Deferred[F, B]
      loserB = F.bracket(s.release)(_ => F.never[B])(_ => pb.complete(b))
      race <- F.start(F.racePair(loserA, loserB))
      _ <- s.acquireN(2L) *> race.cancel
      a <- pa.get
      b <- pb.get
    } yield f(a, b)

    fc <-> F.pure(f(a, b))
  }
}

object ConcurrentLaws {
  def apply[F[_]](implicit F0: Concurrent[F]): ConcurrentLaws[F] = new ConcurrentLaws[F] {
    val F = F0
  }
}
