/*
 * Copyright 2017 Daniel Spiewak
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

import cats.implicits._
import cats.laws._

trait EffectLaws[F[_]] extends AsyncLaws[F] with SyncLaws[F] {
  implicit def F: Effect[F]

  def runAsyncPureProducesRightIO[A](a: A) = {
    val fa = F.pure(a)
    var result: Option[Either[Throwable, A]] = None
    val read = IO { result.get }

    F.runAsync(fa)(e => IO { result = Some(e) }) >> read <-> IO.pure(Right(a))
  }

  def runAsyncRaiseErrorProducesLeftIO[A](t: Throwable) = {
    val fa: F[A] = F.raiseError(t)
    var result: Option[Either[Throwable, A]] = None
    val read = IO { result.get }

    F.runAsync(fa)(e => IO { result = Some(e) }) >> read <-> IO.pure(Left(t))
  }

  def repeatedCallbackIgnored[A](a: A, f: A => A) = {
    var cur = a
    val change = F.delay(cur = f(cur))
    val readResult = IO { cur }

    val double: F[Unit] = F async { cb =>
      cb(Right(()))
      cb(Right(()))
    }

    val test = F.runAsync(double >> change) { _ =>
      IO.pure(())
    }

    test >> readResult <-> IO.pure(f(a))
  }

  lazy val stackSafetyOnRepeatedLeftBinds = {
    val result = (0 until 10000).foldLeft(F.delay(())) { (acc, _) =>
      acc.flatMap(_ => F.delay(()))
    }

    F.runAsync(result)(_ => IO.pure(())).unsafeRunSync() <-> (())
  }

  lazy val stackSafetyOnRepeatedRightBinds = {
    val result = (0 until 10000).foldRight(F.delay(())) { (_, acc) =>
      F.delay(()).flatMap(_ => acc)
    }

    F.runAsync(result)(_ => IO.pure(())).unsafeRunSync() <-> (())
  }

  lazy val stackSafetyOnRepeatedAttempts = {
    val result = (0 until 10000).foldLeft(F.delay(())) { (acc, _) =>
      acc.attempt.map(_ => ())
    }

    F.runAsync(result)(_ => IO.pure(())).unsafeRunSync() <-> (())
  }

  // the following law(s) should really be on MonadError
  def propagateErrorsThroughBindSuspend[A](t: Throwable) = {
    val fa = F.delay[A](throw t).flatMap(x => F.pure(x)).attempt

    var result: Either[Throwable, Either[Throwable, A]] = Left(new AssertionError)
    val read = IO { result }

    F.runAsync(fa)(e => IO { result = e }) >> read <-> IO.pure(Right(Left(t)))
  }

  def propagateErrorsThroughBindAsync[A](t: Throwable) = {
    val fa = F.async[A](_(Left(t))).flatMap(x => F.pure(x)).attempt

    var result: Either[Throwable, Either[Throwable, A]] = Left(new AssertionError)
    val read = IO { result }

    F.runAsync(fa)(e => IO { result = e }) >> read <-> IO.pure(Right(Left(t)))
  }
}

object EffectLaws {
  def apply[F[_]](implicit F0: Effect[F]): EffectLaws[F] = new EffectLaws[F] {
    val F = F0
  }
}
