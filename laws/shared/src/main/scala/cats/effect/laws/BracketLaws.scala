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

trait BracketLaws[F[_], E] extends MonadErrorLaws[F, E] {
  implicit def F: Bracket[F, E]

  def bracketWithPureUnitIsEqvMap[A, B](fa: F[A], f: A => B) =
    F.bracket(fa)(a => f(a).pure[F])((_, _) => F.unit) <-> F.map(fa)(f)

  def bracketWithPureUnitIsEqvFlatMap[A, B](fa: F[A], f: A => F[B]) =
    F.bracket(fa)(f)((_, _) => F.unit) <-> F.flatMap(fa)(f)

  def bracketFailureInAcquisitionRemainsFailure[A, B](e: E, f: A => F[B], release: F[Unit]) =
    F.bracket(F.raiseError[A](e))(f)((_, _) => release) <-> F.raiseError(e)

  def bracketEmitsUseFailure[A](e: E, e2: E, fa: F[A]) =
    F.bracket(fa)(_ => F.raiseError[A](e))((_, _) => F.raiseError(e2)) <-> fa *> F.raiseError(e)
}

object BracketLaws {
  def apply[F[_], E](implicit F0: Bracket[F, E]): BracketLaws[F, E] = new BracketLaws[F, E] {
    val F = F0
  }
}
