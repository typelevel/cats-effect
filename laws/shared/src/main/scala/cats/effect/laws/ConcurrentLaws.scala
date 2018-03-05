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
    val never = F.onCancelRaiseError(F.async[A](_ => ()), e)
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
}

object ConcurrentLaws {
  def apply[F[_]](implicit F0: Concurrent[F]): ConcurrentLaws[F] = new ConcurrentLaws[F] {
    val F = F0
  }
}
