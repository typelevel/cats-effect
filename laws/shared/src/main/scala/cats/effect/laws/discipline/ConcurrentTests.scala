/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats
package effect
package laws
package discipline

import cats.data._
import cats.laws.discipline._
import cats.laws.discipline.SemigroupalTests.Isomorphisms
import org.scalacheck._, Prop.forAll

trait ConcurrentTests[F[_]] extends AsyncTests[F] {
  def laws: ConcurrentLaws[F]

  def concurrent[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](implicit
                                                                       ArbFA: Arbitrary[F[A]],
                                                                       ArbFB: Arbitrary[F[B]],
                                                                       ArbFC: Arbitrary[F[C]],
                                                                       ArbFU: Arbitrary[F[Unit]],
                                                                       ArbFAtoB: Arbitrary[F[A => B]],
                                                                       ArbFBtoC: Arbitrary[F[B => C]],
                                                                       ArbT: Arbitrary[Throwable],
                                                                       CogenA: Cogen[A],
                                                                       CogenB: Cogen[B],
                                                                       CogenC: Cogen[C],
                                                                       CogenT: Cogen[Throwable],
                                                                       EqFA: Eq[F[A]],
                                                                       EqFB: Eq[F[B]],
                                                                       EqFC: Eq[F[C]],
                                                                       EqFU: Eq[F[Unit]],
                                                                       EqT: Eq[Throwable],
                                                                       EqFEitherTU: Eq[F[Either[Throwable, Unit]]],
                                                                       EqFEitherTA: Eq[F[Either[Throwable, A]]],
                                                                       EqEitherTFTA: Eq[EitherT[F, Throwable, A]],
                                                                       EqFABC: Eq[F[(A, B, C)]],
                                                                       EqFInt: Eq[F[Int]],
                                                                       iso: Isomorphisms[F],
                                                                       params: Parameters): RuleSet =
    new RuleSet {
      val name = "concurrent"
      val bases = Nil
      val parents = Seq(async[A, B, C])
      val props = {
        val default = Seq(
          "async cancelable coherence" -> forAll(laws.asyncCancelableCoherence[A] _),
          "async cancelable receives cancel signal" -> forAll(laws.asyncCancelableReceivesCancelSignal[A] _),
          "asyncF registration can be cancelled" -> forAll(laws.asyncFRegisterCanBeCancelled[A] _),
          "bracket release is called on cancel" -> forAll(laws.cancelOnBracketReleases[A, B] _),
          "start then join is identity" -> forAll(laws.startJoinIsIdentity[A] _),
          "join is idempotent" -> forAll(laws.joinIsIdempotent[A] _),
          "start.flatMap(_.cancel) is unit" -> forAll(laws.startCancelIsUnit[A] _),
          "uncancelable mirrors source" -> forAll(laws.uncancelableMirrorsSource[A] _),
          "acquire of bracket is not cancelable" -> forAll(laws.acquireIsNotCancelable[A] _),
          "release of bracket is not cancelable" -> forAll(laws.releaseIsNotCancelable[A] _),
          "race mirrors left winner" -> forAll(laws.raceMirrorsLeftWinner[A] _),
          "race mirrors right winner" -> forAll(laws.raceMirrorsRightWinner[A] _),
          "race cancels loser" -> forAll(laws.raceCancelsLoser[A, B] _),
          "race cancels both" -> forAll(laws.raceCancelsBoth[A, B, C] _),
          "racePair mirrors left winner" -> forAll(laws.racePairMirrorsLeftWinner[A] _),
          "racePair mirrors right winner" -> forAll(laws.racePairMirrorsRightWinner[B] _),
          "racePair cancels loser" -> forAll(laws.racePairCancelsLoser[A, B] _),
          "racePair cancels both" -> forAll(laws.racePairCancelsBoth[A, B, C] _),
          "racePair can join left" -> forAll(laws.racePairCanJoinLeft[A] _),
          "racePair can join right" -> forAll(laws.racePairCanJoinRight[A] _),
          "an action run concurrently with a pure value is the same as just doing that action" ->
            forAll(laws.actionConcurrentWithPureValueIsJustAction[A] _)
        )

        // Activating the tests that detect non-termination only if allowed by Params,
        // because such tests might not be reasonable depending on evaluation model
        if (params.allowNonTerminationLaws)
          default ++ Seq(
            "uncancelable prevents cancellation" -> forAll(laws.uncancelablePreventsCancelation[A] _)
          )
        else
          default
      }
    }
}

object ConcurrentTests {
  def apply[F[_]: Concurrent](implicit cs: ContextShift[F]): ConcurrentTests[F] =
    new ConcurrentTests[F] {
      def laws = ConcurrentLaws[F]
    }
}
