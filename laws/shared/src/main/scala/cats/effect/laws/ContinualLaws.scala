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

package cats
package effect
package laws

import cats.effect.ExitCase.Canceled
import cats.effect.concurrent.Deferred
import cats.implicits._
import cats.laws._

trait ContinualLaws[F[_]] {
  implicit def F: Continual[F]

  def continualMirrorsSource[A](fa: F[A]) =
    F.continual(fa) <-> fa

  def continualCancelationModel[A](a: A)
    (implicit C: Concurrent[F]) = {

    val lh = for {
      start <- Deferred.uncancelable[F, Unit]
      wasCanceled <- C.liftIO(Deferred.uncancelable[IO, A])
      task = start.get >> C.cancelable[A](_ => wasCanceled.complete(a))
      fiber <- C.start(task)
      _ <- fiber.cancel
      _ <- start.complete(())
      r <- C.liftIO(wasCanceled.get)
    } yield r

    F.continual(lh) <-> C.pure(a)
  }

  def onCancelRaiseErrorMirrorsSource[A](fa: F[A], e: Throwable) =
    F.onCancelRaiseError(fa, e) <-> fa

  def onCancelRaiseErrorTerminatesOnCancel[A](e: Throwable)
    (implicit C: Concurrent[F]) = {

    val never = F.onCancelRaiseError(C.never[A], e)
    val received =
      for {
        fiber <- C.start(never)
        _ <- fiber.cancel
        r <- fiber.join
      } yield r
    // We want to guarantee this only in the continual model
    F.continual(received) <-> C.raiseError(e)
  }

  def onCancelRaiseErrorCanCancelSource[A](a: A, e: Throwable)
    (implicit C: Concurrent[F])= {

    val lh = C.liftIO(Deferred.uncancelable[IO, A]).flatMap { effect =>
      val async = C.cancelable[Unit](_ => effect.complete(a))
      C.start(F.onCancelRaiseError(async, e))
        .flatMap(_.cancel) *> C.liftIO(effect.get)
    }
    // We want to guarantee this only in the continual model
    F.continual(lh) <-> C.pure(a)
  }

  def onCancelRaiseErrorResetsCancellationFlag[A](a: A, e: Throwable)
    (implicit C: Concurrent[F]) = {

    val task = F.onCancelRaiseError(C.never[A], e)
    val recovered = task.recoverWith {
      case `e` => C.liftIO(IO.cancelBoundary *> IO(a))
    }
    val lh = C.start(recovered).flatMap(f => f.cancel *> f.join)
    // We want to guarantee this only in the continual model
    F.continual(lh) <-> C.liftIO(IO(a))
  }

  def guaranteeCaseTerminatesOnCancel[A](e: Throwable)
    (implicit C: Concurrent[F]) = {

    val never = C.guaranteeCase(C.never[A]) {
      case Canceled => C.raiseError(e)
      case _ => C.unit
    }

    val received =
      for {
        fiber <- C.start(never)
        _ <- fiber.cancel
        r <- fiber.join
      } yield r

    // We want to guarantee this only in the continual model
    F.continual(received) <-> C.raiseError(e)
  }

  def guaranteeCaseCanCancelSource[A](a: A, e: Throwable)
    (implicit C: Concurrent[F])= {

    val lh = C.liftIO(Deferred.uncancelable[IO, A]).flatMap { effect =>
      val async = C.cancelable[Unit](_ => effect.complete(a))
      val task = C.start(C.guaranteeCase(async) {
        case Canceled => C.raiseError(e)
        case _ => C.unit
      })
      task.flatMap(_.cancel) *> C.liftIO(effect.get)
    }
    // We want to guarantee this only in the continual model
    F.continual(lh) <-> C.pure(a)
  }

  def guaranteeCaseResetsCancellationFlag[A](a: A, e: Throwable)
    (implicit C: Concurrent[F]) = {

    val task = C.guaranteeCase(C.never[A]) {
      case Canceled => C.raiseError(e)
      case _ => C.unit
    }
    val recovered = task.recoverWith {
      case `e` => C.liftIO(IO.cancelBoundary *> IO(a))
    }
    val lh = C.start(recovered).flatMap(f => f.cancel *> f.join)
    // We want to guarantee this only in the continual model
    F.continual(lh) <-> C.liftIO(IO(a))
  }
}

object ContinualLaws {
  def apply[F[_]](implicit F0: Continual[F]): ContinualLaws[F] = new ContinualLaws[F] {
    val F = F0
  }
}
