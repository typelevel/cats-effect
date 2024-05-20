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

import cats.Eq
import cats.effect.kernel.{GenSpawn, Outcome}
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._
import org.scalacheck.Prop.forAll
import org.scalacheck.util.Pretty
import org.typelevel.discipline.Laws

trait GenSpawnTests[F[_], E] extends MonadCancelTests[F, E] with UniqueTests[F] {

  val laws: GenSpawnLaws[F, E]

  @deprecated("revised several constraints", since = "3.2.0")
  protected def spawn[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
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
      ePP: E => Pretty,
      foaPP: F[Outcome[F, E, A]] => Pretty,
      feauPP: F[Either[A, Unit]] => Pretty,
      feuaPP: F[Either[Unit, A]] => Pretty,
      fouPP: F[Outcome[F, E, Unit]] => Pretty): RuleSet = {

    // these are the OLD LAWS retained only for bincompat
    new RuleSet {
      val name = "concurrent"
      val bases: Seq[(String, Laws#RuleSet)] = Nil
      val parents = Seq(
        monadCancel[A, B, C](
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
          EqFEitherEU,
          EqFEitherEA,
          EqFABC,
          EqFInt,
          iso,
          faPP,
          fuPP,
          ePP
        ))

      val props = Seq(
        "race derives from racePair (left)" -> forAll(laws.raceDerivesFromRacePairLeft[A, B] _),
        "race derives from racePair (right)" -> forAll(
          laws.raceDerivesFromRacePairRight[A, B] _),
        "race never non-canceled identity (left)" -> forAll(
          laws.raceNeverNoncanceledIdentityLeft[A] _),
        "race never non-canceled identity (right)" -> forAll(
          laws.raceNeverNoncanceledIdentityRight[A] _),
        // "race left cede yields" -> forAll(laws.raceLeftCedeYields[A] _),
        // "race right cede yields" -> forAll(laws.raceRightCedeYields[A] _),
        "fiber pure is completed pure" -> forAll(laws.fiberPureIsOutcomeCompletedPure[A] _),
        "fiber error is errored" -> forAll(laws.fiberErrorIsOutcomeErrored _),
        "fiber cancelation is canceled" -> laws.fiberCancelationIsOutcomeCanceled,
        "fiber canceled is canceled" -> laws.fiberCanceledIsOutcomeCanceled,
        "fiber never is never" -> laws.fiberNeverIsNever,
        "fiber start of never is unit" -> laws.fiberStartOfNeverIsUnit,
        "never dominates over flatMap" -> forAll(laws.neverDominatesOverFlatMap[A] _),
        "uncancelable race not inherited" -> laws.uncancelableRaceNotInherited,
        "uncancelable canceled is canceled" -> laws.uncancelableCancelCancels,
        "uncancelable start is cancelable" -> laws.uncancelableStartIsCancelable,
        "forceR never is never" -> forAll(laws.forceRNeverIsNever[A] _)
      )
    }
  }

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
      iso: Isomorphisms[F]): RuleSet = {

    new RuleSet {
      val name = "concurrent"
      val bases: Seq[(String, Laws#RuleSet)] = Nil
      val parents = Seq(
        monadCancel[A, B, C](
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
          EqFEitherEU,
          EqFEitherEA,
          EqFABC,
          EqFInt,
          iso
        ))

      val props = Seq(
        "race derives from racePair (left)" -> forAll(laws.raceDerivesFromRacePairLeft[A, B] _),
        "race derives from racePair (right)" -> forAll(
          laws.raceDerivesFromRacePairRight[A, B] _),
        "race never non-canceled identity (left)" -> forAll(
          laws.raceNeverNoncanceledIdentityLeft[A] _),
        "race never non-canceled identity (right)" -> forAll(
          laws.raceNeverNoncanceledIdentityRight[A] _),
        // "race left cede yields" -> forAll(laws.raceLeftCedeYields[A] _),
        // "race right cede yields" -> forAll(laws.raceRightCedeYields[A] _),
        "fiber pure is completed pure" -> forAll(laws.fiberPureIsOutcomeCompletedPure[A] _),
        "fiber error is errored" -> forAll(laws.fiberErrorIsOutcomeErrored _),
        "fiber cancelation is canceled" -> laws.fiberCancelationIsOutcomeCanceled,
        "fiber canceled is canceled" -> laws.fiberCanceledIsOutcomeCanceled,
        "fiber never is never" -> laws.fiberNeverIsNever,
        "fiber start of never is unit" -> laws.fiberStartOfNeverIsUnit,
        "fiber join is guaranteeCase" -> forAll(laws.fiberJoinIsGuaranteeCase[A] _),
        "never dominates over flatMap" -> forAll(laws.neverDominatesOverFlatMap[A] _),
        "uncancelable race not inherited" -> laws.uncancelableRaceNotInherited,
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
