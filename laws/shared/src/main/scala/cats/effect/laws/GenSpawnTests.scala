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
import cats.effect.kernel.{GenSpawn, Outcome}
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._, Prop.forAll
import org.scalacheck.util.Pretty

trait GenSpawnTests[F[_], E] extends MonadCancelTests[F, E] {

  val laws: GenSpawnLaws[F, E]

  def spawn[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
      implicit ArbFA: Arbitrary[F[A]],
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
      iso: Isomorphisms[F],
      faPP: F[A] => Pretty,
      fuPP: F[Unit] => Pretty,
      aFUPP: (A => F[Unit]) => Pretty,
      ePP: E => Pretty,
      foaPP: F[Outcome[F, E, A]] => Pretty,
      feauPP: F[Either[A, Unit]] => Pretty,
      feuaPP: F[Either[Unit, A]] => Pretty,
      fouPP: F[Outcome[F, E, Unit]] => Pretty): RuleSet = {

    new RuleSet {
      val name = "concurrent"
      val bases = Nil
      val parents = Seq(monadCancel[A, B, C])

      val props = Seq(
        "race derives from racePair (left)" -> forAll(laws.raceDerivesFromRacePairLeft[A, B] _),
        "race derives from racePair (right)" -> forAll(
          laws.raceDerivesFromRacePairRight[A, B] _),
        "race canceled identity (left)" -> forAll(laws.raceCanceledIdentityLeft[A] _),
        "race canceled identity (right)" -> forAll(laws.raceCanceledIdentityRight[A] _),
        "race never identity attempt (left)" -> forAll(laws.raceNeverIdentityLeft[A] _),
        "race never identity attempt (right)" -> forAll(laws.raceNeverIdentityRight[A] _),
        // "race left cede yields" -> forAll(laws.raceLeftCedeYields[A] _),
        // "race right cede yields" -> forAll(laws.raceRightCedeYields[A] _),
        "fiber pure is completed pure" -> forAll(laws.fiberPureIsOutcomeCompletedPure[A] _),
        "fiber error is errored" -> forAll(laws.fiberErrorIsOutcomeErrored _),
        "fiber cancelation is canceled" -> laws.fiberCancelationIsOutcomeCanceled,
        "fiber canceled is canceled" -> laws.fiberCanceledIsOutcomeCanceled,
        "fiber never is never" -> laws.fiberNeverIsNever,
        "fiber start of never is unit" -> laws.fiberStartOfNeverIsUnit,
        "never dominates over flatMap" -> forAll(laws.neverDominatesOverFlatMap[A] _),
        "uncancelable race displaces canceled" -> laws.uncancelableRaceDisplacesCanceled,
        "uncancelable race poll canceled identity (left)" -> forAll(
          laws.uncancelableRacePollCanceledIdentityLeft[A] _),
        "uncancelable race poll canceled identity (right)" -> forAll(
          laws.uncancelableRacePollCanceledIdentityRight[A] _),
        "uncancelable canceled is canceled" -> laws.uncancelableCancelCancels,
        "uncancelable start is cancelable" -> laws.uncancelableStartIsCancelable,
        "forceR never is never" -> forAll(laws.forceRNeverIsNever[A] _)
      )
    }
  }
}

object GenSpawnTests {
  def apply[F[_], E](implicit F0: GenSpawn[F, E]): GenSpawnTests[F, E] =
    new GenSpawnTests[F, E] {
      val laws = GenSpawnLaws[F, E]
    }
}
