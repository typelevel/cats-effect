/*
 * Copyright 2019 Daniel Spiewak
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

package ce3
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
      ArbHandlerB: Arbitrary[(A, laws.F.Case[B]) => F[Unit]],
      ArbHandlerU: Arbitrary[(A, laws.F.Case[Unit]) => F[Unit]],
      CogenA: Cogen[A],
      CogenB: Cogen[B],
      CogenC: Cogen[C],
      CogenE: Cogen[E],
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
      faPP: F[A] => Pretty)
      : RuleSet = {

    new RuleSet {
      val name = "bracket"
      val bases = Nil
      val parents = Seq(monadError[A, B, C])

      val props = Seq(
        "bracket pure coherence" -> forAll(laws.bracketPureCoherence[A, B] _),
        "bracket error coherence" -> forAll(laws.bracketErrorCoherence[A] _),
        "bracket flatMap attempt identity" -> forAll(laws.bracketFlatMapAttemptIdentity[A, B] _),
        "bracket raiseError identity" -> forAll(laws.bracketErrorIdentity[A, B] _))
    }
  }
}

object BracketTests {
  def apply[F[_], E](implicit F0: Bracket[F, E]): BracketTests[F, E] { val laws: BracketLaws[F, E] { val F: F0.type } } = new BracketTests[F, E] {
    val laws = BracketLaws[F, E]
  }
}
