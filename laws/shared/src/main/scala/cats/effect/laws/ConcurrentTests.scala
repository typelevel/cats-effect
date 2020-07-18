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
import cats.effect.kernel.{Concurrent, Outcome}
import cats.laws.discipline._
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._, Prop.forAll
import org.scalacheck.util.Pretty

trait ConcurrentTests[F[_], E] extends MonadErrorTests[F, E] {

  val laws: ConcurrentLaws[F, E]

  def concurrent[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
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
      EqFEitherEU: Eq[F[Either[E, Unit]]],
      EqFEitherEA: Eq[F[Either[E, A]]],
      EqFEitherUA: Eq[F[Either[Unit, A]]],
      EqFEitherAU: Eq[F[Either[A, Unit]]],
      EqFEitherEitherEAU: Eq[F[Either[Either[E, A], Unit]]],
      EqFEitherUEitherEA: Eq[F[Either[Unit, Either[E, A]]]],
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
      val parents = Seq(monadError[A, B, C])

      val props = Seq(
        "race is racePair identity (left)" -> forAll(
          laws.raceIsRacePairCancelIdentityLeft[A] _),
        "race is racePair identity (right)" -> forAll(
          laws.raceIsRacePairCancelIdentityRight[A] _),
        "race canceled identity (left)" -> forAll(laws.raceCanceledIdentityLeft[A] _),
        "race canceled identity (right)" -> forAll(laws.raceCanceledIdentityRight[A] _),
        "race never identity attempt (left)" -> forAll(laws.raceNeverIdentityAttemptLeft[A] _),
        "race never identity attempt (right)" -> forAll(laws.raceNeverIdentityAttemptLeft[A] _),
        // "race left cede yields" -> forAll(laws.raceLeftCedeYields[A] _),
        // "race right cede yields" -> forAll(laws.raceRightCedeYields[A] _),
        "fiber pure is completed pure" -> forAll(laws.fiberPureIsOutcomeCompletedPure[A] _),
        "fiber error is errored" -> forAll(laws.fiberErrorIsOutcomeErrored _),
        "fiber cancelation is canceled" -> laws.fiberCancelationIsOutcomeCanceled,
        "fiber canceled is canceled" -> laws.fiberCanceledIsOutcomeCanceled,
        "fiber never is never" -> laws.fiberNeverIsNever,
        "fiber start of never is unit" -> laws.fiberStartOfNeverIsUnit,
        "never dominates over flatMap" -> forAll(laws.neverDominatesOverFlatMap[A] _),
        "uncancelable poll is identity" -> forAll(laws.uncancelablePollIsIdentity[A] _),
        "uncancelable ignored poll eliminates nesting" -> forAll(
          laws.uncancelableIgnoredPollEliminatesNesting[A] _),
        "uncancelable poll inverse nest is uncancelable" -> forAll(
          laws.uncancelablePollInverseNestIsUncancelable[A] _),
        "uncancelable distributes over race attempt (left)" -> forAll(
          laws.uncancelableDistributesOverRaceAttemptLeft[A] _),
        "uncancelable distributes over race attempt (right)" -> forAll(
          laws.uncancelableDistributesOverRaceAttemptRight[A] _),
        "uncancelable race displaces canceled" -> laws.uncancelableRaceDisplacesCanceled,
        "uncancelable race poll canceled identity (left)" -> forAll(
          laws.uncancelableRacePollCanceledIdentityLeft[A] _),
        "uncancelable race poll canceled identity (right)" -> forAll(
          laws.uncancelableRacePollCanceledIdentityRight[A] _),
        "uncancelable canceled is canceled" -> laws.uncancelableCancelCancels,
        "uncancelable start is cancelable" -> laws.uncancelableStartIsCancelable,
        "uncancelable canceled associates right over flatMap" -> forAll(
          laws.uncancelableCanceledAssociatesRightOverFlatMap[A] _),
        "canceled associates left over flatMap" -> forAll(
          laws.canceledAssociatesLeftOverFlatMap[A] _),
        "canceled sequences onCancel in order" -> forAll(
          laws.canceledSequencesOnCancelInOrder _),
        "uncancelable eliminates onCancel" -> forAll(laws.uncancelableEliminatesOnCancel[A] _)
      )
    }
  }
}

object ConcurrentTests {
  def apply[F[_], E](implicit F0: Concurrent[F, E]): ConcurrentTests[F, E] =
    new ConcurrentTests[F, E] {
      val laws = ConcurrentLaws[F, E]
    }
}
