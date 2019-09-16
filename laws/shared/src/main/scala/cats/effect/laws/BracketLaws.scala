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

import cats.implicits._
import cats.laws._

trait BracketLaws[F[_], E] extends MonadErrorLaws[F, E] {
  implicit def F: Bracket[F, E]

  def bracketCaseWithPureUnitIsEqvMap[A, B](fa: F[A], f: A => B) =
    F.bracketCase(fa)(a => f(a).pure[F])((_, _) => F.unit) <-> F.map(fa)(f)

  def bracketCaseWithPureUnitIsUncancelable[A, B](fa: F[A], f: A => F[B]) =
    F.bracketCase(fa)(f)((_, _) => F.unit) <-> F.uncancelable(fa).flatMap(f)

  def bracketCaseFailureInAcquisitionRemainsFailure[A, B](e: E, f: A => F[B], release: F[Unit]) =
    F.bracketCase(F.raiseError[A](e))(f)((_, _) => release) <-> F.raiseError(e)

  def bracketIsDerivedFromBracketCase[A, B](fa: F[A], use: A => F[B], release: A => F[Unit]) =
    F.bracket(fa)(use)(release) <-> F.bracketCase(fa)(use)((a, _) => release(a))

  def uncancelablePreventsCanceledCase[A](fa: F[A], onCancel: F[Unit], onFinish: F[Unit]) =
    F.uncancelable(F.bracketCase(F.unit)(_ => fa) {
      case (_, ExitCase.Canceled) => onCancel
      case _                      => onFinish
    }) <-> F.uncancelable(F.guarantee(fa)(onFinish))

  def acquireAndReleaseAreUncancelable[A, B](fa: F[A], use: A => F[B], release: A => F[Unit]) =
    F.bracket(F.uncancelable(fa))(use)(a => F.uncancelable(release(a))) <-> F.bracket(fa)(use)(release)

  def guaranteeIsDerivedFromBracket[A](fa: F[A], finalizer: F[Unit]) =
    F.guarantee(fa)(finalizer) <-> F.bracket(F.unit)(_ => fa)(_ => finalizer)

  def guaranteeCaseIsDerivedFromBracketCase[A](fa: F[A], finalizer: ExitCase[E] => F[Unit]) =
    F.guaranteeCase(fa)(finalizer) <-> F.bracketCase(F.unit)(_ => fa)((_, e) => finalizer(e))

  def onCancelIsDerivedFromGuaranteeCase[A](fa: F[A], finalizer: F[Unit]) =
    F.onCancel(fa)(finalizer) <-> F.guaranteeCase(fa) {
      case ExitCase.Canceled                      => finalizer
      case ExitCase.Completed | ExitCase.Error(_) => F.unit
    }

  // If MT[_[_], _] is a monad transformer, M[_] is its precursor monad, G[_] is a base (effect) monad such that
  // F[α] = MT[G, α], `fromM` lifts M to F purely wrt to G, and `release` does no G effects, then:
  def bracketPropagatesTransformerEffects[M[_], A, B](
    fromM: M ~> F
  )(acquire: F[A], use: A => F[B], release: A => M[Unit]) =
    F.bracket(acquire)(a => use(a))(a => fromM(release(a))) <->
      F.flatMap(acquire)(a => F.flatMap(use(a))(b => F.as(fromM(release(a)), b)))
}

object BracketLaws {
  def apply[F[_], E](implicit F0: Bracket[F, E]): BracketLaws[F, E] = new BracketLaws[F, E] {
    val F = F0
  }
}
