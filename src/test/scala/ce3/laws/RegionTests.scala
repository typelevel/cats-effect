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

trait RegionTests[R[_[_], _], F[_], E] extends MonadErrorTests[R[F, ?], E] {

  val laws: RegionLaws[R, F, E]

  def region[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
    implicit
      ArbRFA: Arbitrary[R[F, A]],
      ArbFA: Arbitrary[F[A]],
      ArbRFB: Arbitrary[R[F, B]],
      ArbFB: Arbitrary[F[B]],
      ArbRFC: Arbitrary[R[F, C]],
      ArbFC: Arbitrary[F[C]],
      ArbRFU: Arbitrary[R[F, Unit]],
      ArbFU: Arbitrary[F[Unit]],
      ArbRFAtoB: Arbitrary[R[F, A => B]],
      ArbRFBtoC: Arbitrary[R[F, B => C]],
      ArbE: Arbitrary[E],
      CogenA: Cogen[A],
      CogenB: Cogen[B],
      CogenC: Cogen[C],
      CogenE: Cogen[E],
      CogenCase: Cogen[laws.F.Case[_]],
      EqRFA: Eq[R[F, A]],
      EqRFB: Eq[R[F, B]],
      EqRFC: Eq[R[F, C]],
      EqE: Eq[E],
      EqRFEitherEU: Eq[R[F, Either[E, Unit]]],
      EqRFEitherEA: Eq[R[F, Either[E, A]]],
      EqRFABC: Eq[R[F, (A, B, C)]],
      EqRFInt: Eq[R[F, Int]],
      EqRFUnit: Eq[R[F, Unit]],
      iso: Isomorphisms[R[F, ?]])
      : RuleSet = {

    new RuleSet {
      val name = "region"
      val bases = Nil
      val parents = Seq(monadError[A, B, C])

      val props = Seq(
        "empty is bracket . pure" -> forAll(laws.regionEmptyBracketPure[A, B] _),
        "nesting" -> forAll(laws.regionNested[A, B, C] _),
        "flatMap extends" -> forAll(laws.regionExtend[A, B] _),
        "error coherence" -> forAll(laws.regionErrorCoherence[A] _),
        "liftF is open unit" -> forAll(laws.regionLiftFOpenUnit[A] _))
    }
  }
}

object RegionTests {
  def apply[
      R[_[_], _],
      F[_],
      Case0[_],
      E](
    implicit
      F0: Region[R, F, E] { type Case[A] = Case0[A] },
      B: Bracket[F, E] { type Case[A] = Case0[A] })
      : RegionTests[R, F, E] =
    new RegionTests[R, F, E] {
      val laws = RegionLaws[R, F, Case0, E]
    }
}
