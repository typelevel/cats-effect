/*
 * Copyright 2017 Typelevel
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

trait EffectLaws[F[_]] extends AsyncLaws[F] {
  implicit def F: Effect[F]

  def runAsyncPureProducesRightIO[A](a: A) = {
    val fa = F.pure(a)
    var result: Option[Either[Throwable, A]] = None
    val read = IO { result.get }

    F.runAsync(fa)(e => IO { result = Some(e) }) >> read <-> IO.pure(Right(a))
  }

  def runAsyncRaiseErrorProducesLeftIO[A](e: Throwable) = {
    val fa: F[A] = F.raiseError(e)
    var result: Option[Either[Throwable, A]] = None
    val read = IO { result.get }

    F.runAsync(fa)(e => IO { result = Some(e) }) >> read <-> IO.pure(Left(e))
  }

  def runAsyncIgnoresErrorInHandler[A](e: Throwable) = {
    val fa = F.pure(())
    F.runAsync(fa)(_ => IO.raiseError(e)) <-> IO.pure(())
  }

  def repeatedCallbackIgnored[A](a: A, f: A => A) = {
    var cur = a
    val change = F delay { cur = f(cur) }
    val readResult = IO { cur }

    val double: F[Unit] = F async { cb =>
      cb(Right(()))
      cb(Right(()))
    }

    val test = F.runAsync(double >> change) { _ => IO.unit }

    test >> readResult <-> IO.pure(f(a))
  }
}

object EffectLaws {
  def apply[F[_]](implicit F0: Effect[F]): EffectLaws[F] = new EffectLaws[F] {
    val F = F0
  }
}
