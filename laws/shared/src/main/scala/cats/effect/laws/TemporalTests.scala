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

import org.scalacheck._, Prop.forAll
import org.scalacheck.util.Pretty

import scala.concurrent.duration.FiniteDuration

trait TemporalTests[F[_], E] extends ConcurrentTests[F, E] with ClockTests[F] {

  val laws: TemporalLaws[F, E]

  def temporal[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](tolerance: FiniteDuration)(
    implicit
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
      CogenFB: Cogen[F[B]],
      CogenC: Cogen[C],
      CogenE: Cogen[E],
      CogenCaseA: Cogen[Outcome[F, E, A]],
      CogenCaseB: Cogen[Outcome[F, E, B]],
      CogenCaseU: Cogen[Outcome[F, E, Unit]],
      EqFA: Eq[F[A]],
      EqFB: Eq[F[B]],
      EqFC: Eq[F[C]],
      EqFU: Eq[F[Unit]],
      EqE: Eq[E],
      EqFEitherEU: Eq[F[Either[E, Unit]]],
      EqFEitherEA: Eq[F[Either[E, A]]],
      EqFEitherAB: Eq[F[Either[A, B]]],
      EqFEitherUA: Eq[F[Either[Unit, A]]],
      EqFEitherAU: Eq[F[Either[A, Unit]]],
      EqFEitherEitherEAU: Eq[F[Either[Either[E, A], Unit]]],
      EqFEitherUEitherEA: Eq[F[Either[Unit, Either[E, A]]]],
      EqFOutcomeEA: Eq[F[Outcome[F, E, A]]],
      EqFOutcomeEU: Eq[F[Outcome[F, E, Unit]]],
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      OrdFFD: Order[F[FiniteDuration]],
      GroupFD: Group[F[FiniteDuration]],
      exec: F[Boolean] => Prop,
      iso: Isomorphisms[F],
      faPP: F[A] => Pretty,
      fuPP: F[Unit] => Pretty,
      aFUPP: (A => F[Unit]) => Pretty,
      ePP: E => Pretty,
      foaPP: F[Outcome[F, E, A]] => Pretty,
      feauPP: F[Either[A, Unit]] => Pretty,
      feuaPP: F[Either[Unit, A]] => Pretty,
      fouPP: F[Outcome[F, E, Unit]] => Pretty)
      : RuleSet = {

    import laws.F

    implicit val t = Tolerance(tolerance)

    // bugs in scalac*
    implicit val help: Tolerance[F[FiniteDuration]] =
      Tolerance[F[FiniteDuration]](Tolerance.lift[F, FiniteDuration])

    new RuleSet {
      val name = "temporal"
      val bases = Nil
      val parents = Seq(concurrent[A, B, C], clock[A, B, C])

      val props = Seq(
        "monotonic sleep sum identity" -> forAll(laws.monotonicSleepSumIdentity _),
        "sleep race minimum" -> forAll(laws.sleepRaceMinimum _),
        "start sleep maximum" -> forAll(laws.startSleepMaximum _))
    }
  }
}

object TemporalTests {
  def apply[F[_], E](implicit F0: Temporal[F, E]): TemporalTests[F, E] = new TemporalTests[F, E] {
    val laws = TemporalLaws[F, E]
  }
}
