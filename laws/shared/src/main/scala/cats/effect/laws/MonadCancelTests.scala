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
import cats.effect.kernel.{CancelScope, MonadCancel}
import cats.laws.discipline._
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._, Prop.forAll
import org.scalacheck.util.Pretty

trait MonadCancelTests[F[_], E] extends MonadErrorTests[F, E] {

  val laws: MonadCancelLaws[F, E]

  def monadCancel[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
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
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      iso: Isomorphisms[F],
      faPP: F[A] => Pretty,
      fuPP: F[Unit] => Pretty,
      aFUPP: (A => F[Unit]) => Pretty,
      ePP: E => Pretty): RuleSet = {

    new RuleSet {
      val name = "monadCancel"
      val bases = Nil
      val parents = Seq(monadError[A, B, C])

      val props = {
        val common: Seq[(String, Prop)] = Seq(
          "uncancelable poll is identity" -> forAll(laws.uncancelablePollIsIdentity[A] _),
          "uncancelable ignored poll eliminates nesting" -> forAll(
            laws.uncancelableIgnoredPollEliminatesNesting[A] _),
          "uncancelable poll inverse nest is uncancelable" -> forAll(
            laws.uncancelablePollInverseNestIsUncancelable[A] _),
          "canceled sequences onCancel in order" -> forAll(
            laws.canceledSequencesOnCancelInOrder _),
          "uncancelable eliminates onCancel" -> forAll(
            laws.uncancelableEliminatesOnCancel[A] _),
          "forceR discards pure" -> forAll(laws.forceRDiscardsPure[A, B] _),
          "forceR discards error" -> forAll(laws.forceRDiscardsError[A] _),
          "forceR canceled short-circuits" -> forAll(laws.forceRCanceledShortCircuits[A] _),
          "uncancelable finalizers" -> forAll(laws.uncancelableFinalizers[A] _)
        )

        val suffix: Seq[(String, Prop)] = laws.F.rootCancelScope match {
          case CancelScope.Cancelable =>
            Seq(
              "uncancelable canceled associates right over flatMap" -> forAll(
                laws.uncancelableCanceledAssociatesRightOverFlatMap[A] _),
              "canceled associates left over flatMap" -> forAll(
                laws.canceledAssociatesLeftOverFlatMap[A] _)
            )

          case CancelScope.Uncancelable =>
            Seq(
              "uncancelable identity" -> forAll(laws.uncancelableIdentity[A] _),
              "canceled is unit" -> laws.canceledUnitIdentity)
        }

        common ++ suffix
      }
    }
  }
}

object MonadCancelTests {
  def apply[F[_], E](implicit F0: MonadCancel[F, E]): MonadCancelTests[F, E] =
    new MonadCancelTests[F, E] {
      val laws = MonadCancelLaws[F, E]
    }
}
