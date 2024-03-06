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
import cats.effect.kernel.{Async, Outcome, Sync}
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._
import org.scalacheck.Prop.forAll
import org.scalacheck.util.Pretty
import org.typelevel.discipline.Laws

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

trait AsyncTests[F[_]] extends GenTemporalTests[F, Throwable] with SyncTests[F] {

  val laws: AsyncLaws[F]

  @deprecated("revised several constraints", since = "3.2.0")
  protected def async[A, B, C](
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
      ArbE: Arbitrary[Throwable],
      ArbST: Arbitrary[Sync.Type],
      ArbFiniteDuration: Arbitrary[FiniteDuration],
      ArbExecutionContext: Arbitrary[ExecutionContext],
      CogenA: Cogen[A],
      CogenB: Cogen[B],
      CogenC: Cogen[C],
      CogenE: Cogen[Throwable],
      EqFA: Eq[F[A]],
      EqFB: Eq[F[B]],
      EqFC: Eq[F[C]],
      EqFU: Eq[F[Unit]],
      EqE: Eq[Throwable],
      EqFEC: Eq[F[ExecutionContext]],
      EqFAB: Eq[F[Either[A, B]]],
      EqFEitherEU: Eq[F[Either[Throwable, Unit]]],
      EqFEitherEA: Eq[F[Either[Throwable, A]]],
      EqFEitherUA: Eq[F[Either[Unit, A]]],
      EqFEitherAU: Eq[F[Either[A, Unit]]],
      EqFOutcomeEA: Eq[F[Outcome[F, Throwable, A]]],
      EqFOutcomeEU: Eq[F[Outcome[F, Throwable, Unit]]],
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      OrdFFD: Order[F[FiniteDuration]],
      GroupFD: Group[FiniteDuration],
      exec: F[Boolean] => Prop,
      iso: Isomorphisms[F],
      faPP: F[A] => Pretty,
      fuPP: F[Unit] => Pretty,
      ePP: Throwable => Pretty,
      foaPP: F[Outcome[F, Throwable, A]] => Pretty,
      feauPP: F[Either[A, Unit]] => Pretty,
      feuaPP: F[Either[Unit, A]] => Pretty,
      fouPP: F[Outcome[F, Throwable, Unit]] => Pretty): RuleSet = {

    new RuleSet {
      val name = "async"
      val bases: Seq[(String, Laws#RuleSet)] = Nil
      val parents = Seq(
        temporal[A, B, C](
          tolerance,
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
          ArbFiniteDuration,
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
          OrdFFD,
          GroupFD,
          exec,
          iso,
          faPP,
          fuPP,
          ePP,
          foaPP,
          feauPP,
          feuaPP,
          fouPP
        ),
        sync[A, B, C]
      )

      val props = Seq(
        "async right is uncancelable sequenced pure" -> forAll(
          laws.asyncRightIsUncancelableSequencedPure[A] _),
        "async left is uncancelable sequenced raiseError" -> forAll(
          laws.asyncLeftIsUncancelableSequencedRaiseError[A] _),
        "async repeated callback is ignored" -> forAll(laws.asyncRepeatedCallbackIgnored[A] _),
        "async cancel token is unsequenced on complete" -> forAll(
          laws.asyncCancelTokenIsUnsequencedOnCompletion[A] _),
        "async cancel token is unsequenced on error" -> forAll(
          laws.asyncCancelTokenIsUnsequencedOnError[A] _),
        "never is derived from async" -> laws.neverIsDerivedFromAsync[A],
        "executionContext commutativity" -> forAll(laws.executionContextCommutativity[A] _),
        "evalOn local pure" -> forAll(laws.evalOnLocalPure _),
        "evalOn pure identity" -> forAll(laws.evalOnPureIdentity[A] _),
        "evalOn raiseError identity" -> forAll(laws.evalOnRaiseErrorIdentity _),
        "evalOn canceled identity" -> forAll(laws.evalOnCanceledIdentity _),
        "evalOn never identity" -> forAll(laws.evalOnNeverIdentity _)
      )
    }
  }

  def async[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](tolerance: FiniteDuration)(
      implicit ArbFA: Arbitrary[F[A]],
      ArbFB: Arbitrary[F[B]],
      ArbFC: Arbitrary[F[C]],
      ArbFU: Arbitrary[F[Unit]],
      ArbFAtoB: Arbitrary[F[A => B]],
      ArbFBtoC: Arbitrary[F[B => C]],
      ArbE: Arbitrary[Throwable],
      ArbST: Arbitrary[Sync.Type],
      ArbFiniteDuration: Arbitrary[FiniteDuration],
      ArbExecutionContext: Arbitrary[ExecutionContext],
      CogenA: Cogen[A],
      CogenB: Cogen[B],
      CogenC: Cogen[C],
      CogenE: Cogen[Throwable],
      CogenOutcomeFEA: Cogen[Outcome[F, Throwable, A]],
      EqFA: Eq[F[A]],
      EqFB: Eq[F[B]],
      EqFC: Eq[F[C]],
      EqFU: Eq[F[Unit]],
      EqE: Eq[Throwable],
      EqFEC: Eq[F[ExecutionContext]],
      EqFAB: Eq[F[Either[A, B]]],
      EqFEitherEU: Eq[F[Either[Throwable, Unit]]],
      EqFEitherEA: Eq[F[Either[Throwable, A]]],
      EqFEitherUA: Eq[F[Either[Unit, A]]],
      EqFEitherAU: Eq[F[Either[A, Unit]]],
      EqFOutcomeEA: Eq[F[Outcome[F, Throwable, A]]],
      EqFOutcomeEU: Eq[F[Outcome[F, Throwable, Unit]]],
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      OrdFFD: Order[F[FiniteDuration]],
      GroupFD: Group[FiniteDuration],
      exec: F[Boolean] => Prop,
      iso: Isomorphisms[F]): RuleSet = {

    new RuleSet {
      val name = "async"
      val bases: Seq[(String, Laws#RuleSet)] = Nil
      val parents = Seq(
        temporal[A, B, C](tolerance)(
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
          ArbFiniteDuration,
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
          OrdFFD,
          GroupFD,
          exec,
          iso
        ),
        sync[A, B, C]
      )

      val props = Seq(
        "asyncCheckAttempt immediate is pure" -> forAll(
          laws.asyncCheckAttemptImmediateIsPure[A] _),
        "asyncCheckAttempt suspended right is async right" -> forAll(
          laws.asyncCheckAttemptSuspendedRightIsAsyncRight[A] _),
        "asyncCheckAttempt suspended left is async left" -> forAll(
          laws.asyncCheckAttemptSuspendedLeftIsAsyncLeft[A] _),
        "async right is uncancelable sequenced pure" -> forAll(
          laws.asyncRightIsUncancelableSequencedPure[A] _),
        "async left is uncancelable sequenced raiseError" -> forAll(
          laws.asyncLeftIsUncancelableSequencedRaiseError[A] _),
        "async repeated callback is ignored" -> forAll(laws.asyncRepeatedCallbackIgnored[A] _),
        "async cancel token is unsequenced on complete" -> forAll(
          laws.asyncCancelTokenIsUnsequencedOnCompletion[A] _),
        "async cancel token is unsequenced on error" -> forAll(
          laws.asyncCancelTokenIsUnsequencedOnError[A] _),
        "never is derived from async" -> laws.neverIsDerivedFromAsync[A],
        "executionContext commutativity" -> forAll(laws.executionContextCommutativity[A] _),
        "evalOn local pure" -> forAll(laws.evalOnLocalPure _),
        "evalOn pure identity" -> forAll(laws.evalOnPureIdentity[A] _),
        "evalOn raiseError identity" -> forAll(laws.evalOnRaiseErrorIdentity _),
        "evalOn canceled identity" -> forAll(laws.evalOnCanceledIdentity _),
        "evalOn never identity" -> forAll(laws.evalOnNeverIdentity _),
        "syncStep identity" -> forAll(laws.syncStepIdentity[A] _)
      )
    }
  }
}

object AsyncTests {
  def apply[F[_]](implicit F0: Async[F]): AsyncTests[F] =
    new AsyncTests[F] {
      val laws = AsyncLaws[F]
    }
}
