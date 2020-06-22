/*
 * Copyright 2020 Typelevel
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

package cats.effect
package laws

import cats.Eq
import cats.data.EitherT
import cats.laws.discipline._
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._, Prop.forAll
import org.scalacheck.util.Pretty

trait BracketTests[F[_], E] extends MonadErrorTests[F, E] {

  val laws: BracketLaws[F, E]

  def bracket[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
    implicit
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
      CogenCaseA: Cogen[laws.F.Case[A]],
      CogenCaseB: Cogen[laws.F.Case[B]],
      CogenCaseU: Cogen[laws.F.Case[Unit]],
      EqFA: Eq[F[A]],
      EqFB: Eq[F[B]],
      EqFC: Eq[F[C]],
      EqFU: Eq[F[Unit]],
      EqE: Eq[E],
      EqFEitherEU: Eq[F[Either[E, Unit]]],
      EqFEitherEA: Eq[F[Either[E, A]]],
      EqEitherTFEA: Eq[EitherT[F, E, A]],
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      iso: Isomorphisms[F],
      faPP: F[A] => Pretty,
      fbPP: F[B] => Pretty,
      fuPP: F[Unit] => Pretty,
      ePP: E => Pretty,
      feeaPP: F[Either[E, A]] => Pretty,
      feeuPP: F[Either[E, Unit]] => Pretty)
      : RuleSet = {

    new RuleSet {
      val name = "bracket"
      val bases = Nil
      val parents = Seq(monadError[A, B, C])

      val props = Seq(
        "bracket pure coherence" -> forAll(laws.bracketPureCoherence[A, B] _),
        "bracket error coherence" -> forAll(laws.bracketErrorCoherence[A] _),
        "bracket acquire raiseError identity" -> forAll(laws.bracketAcquireErrorIdentity[A, B] _),
        "bracket release raiseError ignore" -> forAll(laws.bracketReleaseErrorIgnore _),
        "bracket body identity" -> forAll(laws.bracketBodyIdentity[A] _),
        "onCase defined by bracketCase" -> forAll(laws.onCaseDefinedByBracketCase[A] _))
    }
  }
}

object BracketTests {
  def apply[F[_], E](implicit F0: Bracket[F, E]): BracketTests[F, E] { val laws: BracketLaws[F, E] { val F: F0.type } } = new BracketTests[F, E] {
    val laws: BracketLaws[F, E] { val F: F0.type } = BracketLaws[F, E]
  }
}
