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

import cats.{Eq, Group, Order}
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._
import org.scalacheck.util.Pretty

import scala.concurrent.duration.FiniteDuration

trait TemporalRegionTests[R[_[_], _], F[_], E] extends TemporalTests[R[F, *], E] with ConcurrentRegionTests[R, F, E] {

  val laws: TemporalRegionLaws[R, F, E]

  def temporalRegion[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](tolerance: FiniteDuration)(
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
      ArbFiniteDuration: Arbitrary[FiniteDuration],
      CogenA: Cogen[A],
      CogenB: Cogen[B],
      CogenRFB: Cogen[R[F, B]],
      CogenC: Cogen[C],
      CogenE: Cogen[E],
      CogenCase: Cogen[laws.F.Case[_]],
      CogenCaseA: Cogen[Outcome[R[F, *], E, A]],
      CogenCaseB: Cogen[Outcome[R[F, *], E, B]],
      CogenCaseU: Cogen[Outcome[R[F, *], E, Unit]],
      EqFA: Eq[R[F, A]],
      EqFB: Eq[R[F, B]],
      EqFC: Eq[R[F, C]],
      EqFU: Eq[R[F, Unit]],
      EqE: Eq[E],
      EqFEitherEU: Eq[R[F, Either[E, Unit]]],
      EqFEitherEA: Eq[R[F, Either[E, A]]],
      EqFEitherAB: Eq[R[F, Either[A, B]]],
      EqFEitherUA: Eq[R[F, Either[Unit, A]]],
      EqFEitherAU: Eq[R[F, Either[A, Unit]]],
      EqFEitherEitherEAU: Eq[R[F, Either[Either[E, A], Unit]]],
      EqFEitherUEitherEA: Eq[R[F, Either[Unit, Either[E, A]]]],
      EqFOutcomeEA: Eq[R[F, Outcome[R[F, *], E, A]]],
      EqFOutcomeEU: Eq[R[F, Outcome[R[F, *], E, Unit]]],
      EqFABC: Eq[R[F, (A, B, C)]],
      EqFInt: Eq[R[F, Int]],
      OrdFFD: Order[R[F, FiniteDuration]],
      GroupFD: Group[R[F, FiniteDuration]],
      exec: R[F, Boolean] => Prop,
      iso: Isomorphisms[R[F, *]],
      faPP: R[F, A] => Pretty,
      fbPP: R[F, B] => Pretty,
      fuPP: R[F, Unit] => Pretty,
      aFUPP: (A => R[F, Unit]) => Pretty,
      ePP: E => Pretty,
      foaPP: F[Outcome[R[F, *], E, A]] => Pretty,
      feauPP: R[F, Either[A, Unit]] => Pretty,
      feuaPP: R[F, Either[Unit, A]] => Pretty,
      fouPP: R[F, Outcome[R[F, *], E, Unit]] => Pretty)
      : RuleSet = {

    new RuleSet {
      val name = "temporal (region)"
      val bases = Nil
      val parents = Seq(temporal[A, B, C](tolerance), concurrentRegion[A, B, C])

      val props = Seq()
    }
  }
}

object TemporalRegionTests {
  def apply[
      R[_[_], _],
      F[_],
      E](
    implicit
      F0: Temporal[R[F, *], E] with Region[R, F, E],
      B0: Bracket.Aux[F, E, Outcome[R[F, *], E, *]])
      : TemporalRegionTests[R, F, E] = new TemporalRegionTests[R, F, E] {
    val laws = TemporalRegionLaws[R, F, E]
  }
}
