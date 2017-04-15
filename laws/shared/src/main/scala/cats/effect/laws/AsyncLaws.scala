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

trait AsyncLaws[F[_]] extends MonadErrorLaws[F, Throwable] {
  implicit def F: Async[F]

  def asyncRightIsPure[A](a: A) =
    F.async[A](_(Right(a))) <-> F.pure(a)

  def asyncLeftIsRaiseError[A](t: Throwable) =
    F.async[A](_(Left(t))) <-> F.raiseError(t)

  def thrownInRegisterIsRaiseError[A](t: Throwable) =
    F.async[A](_ => throw t) <-> F.raiseError(t)

  def repeatedAsyncEvaluationNotMemoized[A](a: A, f: A => A) = {
    var cur = a

    val change: F[Unit] = F async { cb =>
      cur = f(cur)
      cb(Right(()))
    }

    val read: F[A] = F.async(_(Right(cur)))

    change >> change >> read <-> F.pure(f(f(a)))
  }
}

object AsyncLaws {
  def apply[F[_]](implicit F0: Async[F]): AsyncLaws[F] = new AsyncLaws[F] {
    val F = F0
  }
}
