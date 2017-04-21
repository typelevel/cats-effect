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

trait SyncLaws[F[_]] extends MonadErrorLaws[F, Throwable] {
  implicit def F: Sync[F]

  def delayConstantIsPure[A](a: A) =
    F.delay(a) <-> F.pure(a)

  def suspendConstantIsPureJoin[A](fa: F[A]) =
    F.suspend(fa) <-> F.flatten(F.pure(fa))

  def delayThrowIsRaiseError[A](t: Throwable) =
    F.delay[A](throw t) <-> F.raiseError(t)

  def suspendThrowIsRaiseError[A](t: Throwable) =
    F.suspend[A](throw t) <-> F.raiseError(t)

  def unsequencedDelayIsNoop[A](a: A, f: A => A) = {
    var cur = a
    val change = F.delay(cur = f(cur))
    val _ = change

    F.delay(cur) <-> F.pure(a)
  }

  def repeatedSyncEvaluationNotMemoized[A](a: A, f: A => A) = {
    var cur = a
    val change = F.delay(cur = f(cur))
    val read = F.delay(cur)

    change >> change >> read <-> F.pure(f(f(a)))
  }
}

object SyncLaws {
  def apply[F[_]](implicit F0: Sync[F]): SyncLaws[F] = new SyncLaws[F] {
    val F = F0
  }
}
