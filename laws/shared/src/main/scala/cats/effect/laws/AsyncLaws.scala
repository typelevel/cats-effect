/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator

import cats.effect.ExitCase.{Completed, Error}
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import cats.laws._

import scala.util.Either

trait AsyncLaws[F[_]] extends SyncLaws[F] {
  implicit def F: Async[F]

  def asyncRightIsPure[A](a: A) =
    F.async[A](_(Right(a))) <-> F.pure(a)

  def asyncLeftIsRaiseError[A](e: Throwable) =
    F.async[A](_(Left(e))) <-> F.raiseError(e)

  def repeatedAsyncEvaluationNotMemoized[A](a: A, f: A => A) = F.suspend {
    val cur = new AtomicReference[A](a)
    val op = new UnaryOperator[A] {
      override def apply(t: A): A = f(t)
    }


    val change: F[Unit] = F.async { cb =>
      val _ = cur.updateAndGet(op)
      cb(Right(()))
    }

    val read: F[A] = F.delay(cur.get())

    change *> change *> read
  } <-> F.pure(f(f(a)))

  def repeatedAsyncFEvaluationNotMemoized[A](a: A, f: A => A) = {
    val cur = Ref.unsafe[F, A](a)

    val change: F[Unit] = F.asyncF { cb =>
      cur.update(f).map(_ => cb(Right(())))
    }

    val read: F[A] = cur.get

    change *> change *> read <-> F.pure(f(f(a)))
  }

  def repeatedCallbackIgnored[A](a: A, f: A => A) = {
    val cur = Ref.unsafe[F, A](a)
    val change = cur.update(f)
    val readResult = cur.get

    val double: F[Unit] = F.async { cb =>
      cb(Right(()))
      cb(Right(()))
    }

    double *> change *> readResult <-> F.delay(f(a))
  }

  def propagateErrorsThroughBindAsync[A](t: Throwable) = {
    val fa = F.attempt(F.async[A](_(Left(t))).flatMap(x => F.pure(x)))
    fa <-> F.pure(Left(t))
  }

  def neverIsDerivedFromAsync[A] =
    F.never[A] <-> F.async[A]( _ => ())

  def asyncCanBeDerivedFromAsyncF[A](k: (Either[Throwable, A] => Unit) => Unit) =
    F.async(k) <-> F.asyncF(cb => F.delay(k(cb)))

  def bracketReleaseIsCalledOnCompletedOrError[A, B](fa: F[A], b: B) = {
    val lh = Deferred.uncancelable[F, B].flatMap { promise =>
      val br = F.bracketCase(F.delay(promise)) { _ =>
        fa
      } {
        case (r, Completed | Error(_)) => r.complete(b)
        case _ => F.unit
      }
      // Start and forget
      // we attempt br because even if fa fails, we expect the release function
      // to run and set the promise.
      F.asyncF[Unit](cb => F.delay(cb(Right(()))) *> br.attempt.as(())) *> promise.get
    }
    lh <-> F.pure(b)
  }
}

object AsyncLaws {
  def apply[F[_]](implicit F0: Async[F]): AsyncLaws[F] = new AsyncLaws[F] {
    val F = F0
  }
}
