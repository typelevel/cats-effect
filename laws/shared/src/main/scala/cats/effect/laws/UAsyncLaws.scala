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

import cats.implicits._
import cats.laws._


trait UAsyncLaws[F[_]] extends USyncLaws[F] {
  implicit def F: UAsync[F]

  def asyncRightIsPureRight[A](a: A) =
    F.asyncCatch[A](_(Right(a))) <-> F.pure(Right(a))

  def asyncLeftIsPureRight[A](e: Throwable) =
    F.asyncCatch[A](_(Left(e))) <-> F.pure(Left(e))

  def repeatedAsyncEvaluationNotMemoized[A](a: A, f: A => A) = {
    var cur = a

    val change: F[Unit] = F asyncUnit { cb =>
      cur = f(cur)
      cb(Right(()))
    }

    val read: F[Either[Throwable, A]] = F.delayCatch(cur)

    change *> change *> read <-> F.pure(Right(f(f(a))))
  }

  def repeatedCallbackIgnored[A](a: A, f: A => A) = {
    var cur = a
    val change = F.delayUnit { cur = f(cur) }
    val readResult = F.delayCatch { cur }

    val double: F[Unit] = F.asyncUnit { cb =>
      cb(Right(()))
      cb(Right(()))
    }

    double *> change *> readResult <-> F.delayCatch(f(a))
  }
}
object UAsyncLaws {
  def apply[F[_]](implicit F0: UAsync[F]): UAsyncLaws[F] = new UAsyncLaws[F] {
    val F = F0
  }
}
