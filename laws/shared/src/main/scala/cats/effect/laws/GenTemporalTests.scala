/*
 * Copyright 2020-2024 Typelevel
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
import cats.effect.kernel.{GenTemporal, Outcome}
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._
import org.scalacheck.Prop.forAll
import org.scalacheck.util.Pretty
import org.typelevel.discipline.Laws

import scala.concurrent.duration.FiniteDuration

trait GenTemporalTests[F[_], E] extends GenSpawnTests[F, E] with ClockTests[F] {

  val laws: GenTemporalLaws[F, E]

  @deprecated("revised several constraints", since = "3.2.0")
  protected def temporal[A, B, C](
      implicit tolerance: FiniteDuration,
      AA: Arbitrary[A],
      AE: Eq[A],
      BA: Arbitrary[B],
      BE: Eq[B],
      CA: Arbitrary[C],
      CE: Eq[C],
      ArbFA: Arbitrary[F[A]],
      ArbFB: Arbitrary[F[B]],
      ArbFC: Arbitrary[F[C]],
      ArbFU: Arbitrary[F[Unit]],
      ArbFAtoB: Arbitrary[F[A => B]],
      ArbFBtoC: Arbitrary[F[B => C]],
      ArbE: Arbitrary[E],
      ArbFiniteDuration: Arbitrary[FiniteDuration],
      CogenA: Cogen[A],
      CogenB: Cogen[B],
      CogenC: Cogen[C],
      CogenE: Cogen[E],
      EqFA: Eq[F[A]],
      EqFB: Eq[F[B]],
      EqFC: Eq[F[C]],
      EqFU: Eq[F[Unit]],
      EqE: Eq[E],
      EqFAB: Eq[F[Either[A, B]]],
      EqFEitherEU: Eq[F[Either[E, Unit]]],
      EqFEitherEA: Eq[F[Either[E, A]]],
      EqFEitherUA: Eq[F[Either[Unit, A]]],
      EqFEitherAU: Eq[F[Either[A, Unit]]],
      EqFOutcomeEA: Eq[F[Outcome[F, E, A]]],
      EqFOutcomeEU: Eq[F[Outcome[F, E, Unit]]],
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      OrdFFD: Order[F[FiniteDuration]],
      GroupFD: Group[FiniteDuration],
      exec: F[Boolean] => Prop,
      iso: Isomorphisms[F],
      faPP: F[A] => Pretty,
      fuPP: F[Unit] => Pretty,
      ePP: E => Pretty,
      foaPP: F[Outcome[F, E, A]] => Pretty,
      feauPP: F[Either[A, Unit]] => Pretty,
      feuaPP: F[Either[Unit, A]] => Pretty,
      fouPP: F[Outcome[F, E, Unit]] => Pretty): RuleSet = {

    import laws.F

    implicit val t = Tolerance(tolerance)

    new RuleSet {
      val name = "temporal"
      val bases: Seq[(String, Laws#RuleSet)] = Nil
      val parents = Seq(
        spawn[A, B, C](
          implicitly[Arbitrary[A]],
          implicitly[Eq[A]],
          implicitly[Arbitrary[B]],
          implicitly[Eq[B]],
          implicitly[Arbitrary[C]],
          implicitly[Eq[C]],
          ArbFA,
          ArbFB,
          ArbFC,
          ArbFU,
          ArbFAtoB,
          ArbFBtoC,
          ArbE,
          CogenA,
          CogenB,
          CogenC,
          CogenE,
          EqFA,
          EqFB,
          EqFC,
          EqFU,
          EqE,
          EqFAB,
          EqFEitherEU,
          EqFEitherEA,
          EqFEitherUA,
          EqFEitherAU,
          EqFOutcomeEA,
          EqFOutcomeEU,
          EqFABC,
          EqFInt,
          iso,
          faPP,
          fuPP,
          ePP,
          foaPP,
          feauPP,
          feuaPP,
          fouPP
        ),
        clock
      )

      val props = Seq(
        "monotonic sleep sum identity" -> forAll(laws.monotonicSleepSumIdentity _),
        "sleep race minimum" -> forAll(laws.sleepRaceMinimum _),
        "start sleep maximum" -> forAll(laws.startSleepMaximum _)
      )
    }
  }

  def temporal[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](tolerance: FiniteDuration)(
      implicit ArbFA: Arbitrary[F[A]],
      ArbFB: Arbitrary[F[B]],
      ArbFC: Arbitrary[F[C]],
      ArbFU: Arbitrary[F[Unit]],
      ArbFAtoB: Arbitrary[F[A => B]],
      ArbFBtoC: Arbitrary[F[B => C]],
      ArbE: Arbitrary[E],
      ArbFiniteDuration: Arbitrary[FiniteDuration],
      CogenA: Cogen[A],
      CogenB: Cogen[B],
      CogenC: Cogen[C],
      CogenE: Cogen[E],
      CogenOutcomeFEA: Cogen[Outcome[F, E, A]],
      EqFA: Eq[F[A]],
      EqFB: Eq[F[B]],
      EqFC: Eq[F[C]],
      EqFU: Eq[F[Unit]],
      EqE: Eq[E],
      EqFAB: Eq[F[Either[A, B]]],
      EqFEitherEU: Eq[F[Either[E, Unit]]],
      EqFEitherEA: Eq[F[Either[E, A]]],
      EqFEitherUA: Eq[F[Either[Unit, A]]],
      EqFEitherAU: Eq[F[Either[A, Unit]]],
      EqFOutcomeEA: Eq[F[Outcome[F, E, A]]],
      EqFOutcomeEU: Eq[F[Outcome[F, E, Unit]]],
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      OrdFFD: Order[F[FiniteDuration]],
      GroupFD: Group[FiniteDuration],
      exec: F[Boolean] => Prop,
      iso: Isomorphisms[F]): RuleSet = {

    import laws.F

    implicit val t = Tolerance(tolerance)

    new RuleSet {
      val name = "temporal"
      val bases: Seq[(String, Laws#RuleSet)] = Nil
      val parents = Seq(
        spawn[A, B, C](
          implicitly[Arbitrary[A]],
          implicitly[Eq[A]],
          implicitly[Arbitrary[B]],
          implicitly[Eq[B]],
          implicitly[Arbitrary[C]],
          implicitly[Eq[C]],
          ArbFA,
          ArbFB,
          ArbFC,
          ArbFU,
          ArbFAtoB,
          ArbFBtoC,
          ArbE,
          CogenA,
          CogenB,
          CogenC,
          CogenE,
          CogenOutcomeFEA,
          EqFA,
          EqFB,
          EqFC,
          EqFU,
          EqE,
          EqFAB,
          EqFEitherEU,
          EqFEitherEA,
          EqFEitherUA,
          EqFEitherAU,
          EqFOutcomeEA,
          EqFOutcomeEU,
          EqFABC,
          EqFInt,
          iso
        ),
        clock
      )

      val props = Seq(
        "monotonic sleep sum identity" -> forAll(laws.monotonicSleepSumIdentity _),
        "sleep race minimum" -> forAll(laws.sleepRaceMinimum _),
        "start sleep maximum" -> forAll(laws.startSleepMaximum _)
      )
    }
  }
}

object GenTemporalTests {
  def apply[F[_], E](implicit F0: GenTemporal[F, E]): GenTemporalTests[F, E] =
    new GenTemporalTests[F, E] {
      val laws = GenTemporalLaws[F, E]
    }
}
