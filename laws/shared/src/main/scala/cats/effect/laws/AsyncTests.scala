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
import cats.effect.kernel.{Async, Outcome}
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._, Prop.forAll
import org.scalacheck.util.Pretty

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

trait AsyncTests[F[_]] extends TemporalTests[F, Throwable] with SyncTests[F] {

  val laws: AsyncLaws[F]

  def async[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](tolerance: FiniteDuration)(
      implicit ArbFA: Arbitrary[F[A]],
      ArbFB: Arbitrary[F[B]],
      ArbFC: Arbitrary[F[C]],
      ArbFU: Arbitrary[F[Unit]],
      ArbFAtoB: Arbitrary[F[A => B]],
      ArbFBtoC: Arbitrary[F[B => C]],
      ArbE: Arbitrary[Throwable],
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
      EqFEitherEU: Eq[F[Either[Throwable, Unit]]],
      EqFEitherEA: Eq[F[Either[Throwable, A]]],
      EqFEitherUA: Eq[F[Either[Unit, A]]],
      EqFEitherAU: Eq[F[Either[A, Unit]]],
      EqFEitherEitherEAU: Eq[F[Either[Either[Throwable, A], Unit]]],
      EqFEitherUEitherEA: Eq[F[Either[Unit, Either[Throwable, A]]]],
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
      aFUPP: (A => F[Unit]) => Pretty,
      ePP: Throwable => Pretty,
      foaPP: F[Outcome[F, Throwable, A]] => Pretty,
      feauPP: F[Either[A, Unit]] => Pretty,
      feuaPP: F[Either[Unit, A]] => Pretty,
      fouPP: F[Outcome[F, Throwable, Unit]] => Pretty): RuleSet = {

    new RuleSet {
      val name = "async"
      val bases = Nil
      val parents = Seq(temporal[A, B, C](tolerance), sync[A, B, C])

      val props = Seq(
        "async right is pure" -> forAll(laws.asyncRightIsPure[A] _),
        "async left is raiseError" -> forAll(laws.asyncLeftIsRaiseError[A] _),
        "async repeated callback is ignored" -> forAll(laws.asyncRepeatedCallbackIgnored[A] _),
        "async cancel token is unsequenced on complete" -> forAll(
          laws.asyncCancelTokenIsUnsequencedOnCompletion[A] _),
        "async cancel token is unsequenced on error" -> forAll(
          laws.asyncCancelTokenIsUnsequencedOnError[A] _),
        // "async cancel token is sequenced on cancel" -> forAll(laws.asyncCancelTokenIsSequencedOnCancel _),
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
}

object AsyncTests {
  def apply[F[_]](implicit F0: Async[F]): AsyncTests[F] =
    new AsyncTests[F] {
      val laws = AsyncLaws[F]
    }
}
