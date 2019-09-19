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
package discipline

import cats.data._
import cats.laws.discipline._
import cats.laws.discipline.SemigroupalTests.Isomorphisms
import cats.effect.laws.discipline.arbitrary.catsEffectLawsCogenForExitCase
import org.scalacheck._
import Prop.forAll

trait BracketTests[F[_], E] extends MonadErrorTests[F, E] {
  def laws: BracketLaws[F, E]

  def bracket[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](implicit
                                                                    ArbFA: Arbitrary[F[A]],
                                                                    ArbFB: Arbitrary[F[B]],
                                                                    ArbFC: Arbitrary[F[C]],
                                                                    ArbFU: Arbitrary[F[Unit]],
                                                                    ArbFAtoB: Arbitrary[F[A => B]],
                                                                    ArbFBtoC: Arbitrary[F[B => C]],
                                                                    ArbE: Arbitrary[E],
                                                                    CogenA: Cogen[A],
                                                                    CogenB: Cogen[B],
                                                                    CogenC: Cogen[C],
                                                                    CogenE: Cogen[E],
                                                                    EqFA: Eq[F[A]],
                                                                    EqFB: Eq[F[B]],
                                                                    EqFC: Eq[F[C]],
                                                                    EqE: Eq[E],
                                                                    EqFEitherEU: Eq[F[Either[E, Unit]]],
                                                                    EqFEitherEA: Eq[F[Either[E, A]]],
                                                                    EqEitherTFEA: Eq[EitherT[F, E, A]],
                                                                    EqFABC: Eq[F[(A, B, C)]],
                                                                    EqFInt: Eq[F[Int]],
                                                                    iso: Isomorphisms[F]): RuleSet =
    new RuleSet {
      val name = "bracket"
      val bases = Nil
      val parents = Seq(monadError[A, B, C])

      val props = Seq(
        "bracketCase with pure unit on release is eqv to map" -> forAll(laws.bracketCaseWithPureUnitIsEqvMap[A, B] _),
        "bracketCase with failure in acquisition remains failure" -> forAll(
          laws.bracketCaseFailureInAcquisitionRemainsFailure[A, B] _
        ),
        "bracketCase with pure unit on release is eqv to uncancelable(..).flatMap" -> forAll(
          laws.bracketCaseWithPureUnitIsUncancelable[A, B] _
        ),
        "bracket is derived from bracketCase" -> forAll(laws.bracketIsDerivedFromBracketCase[A, B] _),
        "uncancelable prevents Cancelled case" -> forAll(laws.uncancelablePreventsCanceledCase[A] _),
        "acquire and release of bracket are uncancelable" -> forAll(laws.acquireAndReleaseAreUncancelable[A, B] _),
        "guarantee is derived from bracket" -> forAll(laws.guaranteeIsDerivedFromBracket[A] _),
        "guaranteeCase is derived from bracketCase" -> forAll(laws.guaranteeCaseIsDerivedFromBracketCase[A] _),
        "onCancel is derived from guaranteeCase" -> forAll(laws.onCancelIsDerivedFromGuaranteeCase[A] _)
      )
    }

  def bracketTrans[M[_], A: Arbitrary: Eq, B: Arbitrary: Eq](fromM: M ~> F)(implicit
                                                                            ArbFA: Arbitrary[F[A]],
                                                                            ArbFB: Arbitrary[F[B]],
                                                                            ArbMU: Arbitrary[M[Unit]],
                                                                            CogenA: Cogen[A],
                                                                            EqFB: Eq[F[B]]): RuleSet =
    new RuleSet {
      val name = "bracket"
      val bases = Nil
      val parents = Nil

      val props = Seq(
        "bracket propagates transformer effects" -> forAll(laws.bracketPropagatesTransformerEffects[M, A, B](fromM) _)
      )
    }
}

object BracketTests {
  def apply[F[_], E](implicit ev: Bracket[F, E]): BracketTests[F, E] = new BracketTests[F, E] {
    def laws = BracketLaws[F, E]
  }
}
