/*
 * Copyright 2020 Daniel Spiewak
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

trait ConcurrentTests[F[_], E] extends MonadErrorTests[F, E] {

  val laws: ConcurrentLaws[F, E]

  def concurrent[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
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
      CogenFB: Cogen[F[B]],
      CogenC: Cogen[C],
      CogenE: Cogen[E],
      CogenCaseA: Cogen[ExitCase[F, E, A]],
      CogenCaseB: Cogen[ExitCase[F, E, B]],
      CogenCaseU: Cogen[ExitCase[F, E, Unit]],
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
      EqFExitCaseEA: Eq[F[ExitCase[F, E, A]]],
      EqFExitCaseEU: Eq[F[ExitCase[F, E, Unit]]],
      EqEitherTFEA: Eq[EitherT[F, E, A]],
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      iso: Isomorphisms[F],
      faPP: F[A] => Pretty,
      fuPP: F[Unit] => Pretty,
      aFUPP: (A => F[Unit]) => Pretty,
      ePP: E => Pretty)
      : RuleSet = {

    new RuleSet {
      val name = "concurrent"
      val bases = Nil
      val parents = Seq(monadError[A, B, C])

      val props = Seq(
        "race is racePair idenitty" -> forAll(laws.raceIsRacePairCancelIdentity[A, B] _),
        "race left error yields" -> forAll(laws.raceLeftErrorYields[A] _),
        "race right error yields" -> forAll(laws.raceRightErrorYields[A] _),
        "race left canceled yields" -> forAll(laws.raceLeftCanceledYields[A] _),
        "race right canceled yields" -> forAll(laws.raceRightCanceledYields[A] _),
        "fiber pure is completed pure" -> forAll(laws.fiberPureIsCompletedPure[A] _),
        "fiber error is errored" -> forAll(laws.fiberErrorIsErrored _),
        "fiber cancelation is canceled" -> laws.fiberCancelationIsCanceled,
        "fiber of canceled is canceled" -> laws.fiberOfCanceledIsCanceled,
        "uncancelable poll is identity" -> forAll(laws.uncancelablePollIsIdentity[A] _),
        "uncancelable fiber will complete" -> forAll(laws.uncancelableFiberBodyWillComplete[A] _),
        "uncancelable of canceled is pure" -> forAll(laws.uncancelableOfCanceledIsPure[A] _))
    }
  }
}

object ConcurrentTests {
  def apply[F[_], E](implicit F0: Concurrent[F, E]): ConcurrentTests[F, E] = new ConcurrentTests[F, E] {
    val laws = ConcurrentLaws[F, E]
  }
}
