/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

import cats.laws.discipline._
import cats.laws.discipline.SemigroupalTests.Isomorphisms
import org.scalacheck._, Prop.forAll

trait UConcurrentTests[F[_]] extends UAsyncTests[F] {
  def laws: UConcurrentLaws[F]

  def uconcurrent[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
    implicit
    ArbFA: Arbitrary[F[A]],
    ArbFB: Arbitrary[F[B]],
    ArbFC: Arbitrary[F[C]],
    ArbFAtoB: Arbitrary[F[A => B]],
    ArbFBtoC: Arbitrary[F[B => C]],
    ArbT: Arbitrary[Throwable],
    CogenA: Cogen[A],
    CogenB: Cogen[B],
    CogenC: Cogen[C],
    EqFA: Eq[F[A]],
    EqFB: Eq[F[B]],
    EqFC: Eq[F[C]],
    EqFU: Eq[F[Unit]],
    EqFEitherTA: Eq[F[Either[Throwable, A]]],
    EqFABC: Eq[F[(A, B, C)]],
    EqFInt: Eq[F[Int]],
    iso: Isomorphisms[F],
    params: Parameters): RuleSet = {

    new RuleSet {
      val name = "uconcurrent"
      val bases = Nil
      val parents = Seq(uasync[A, B, C])
      val props = Seq(
        "asyncCatch cancelable coherence" -> forAll(laws.asyncCatchCancelableCatchCoherence[A] _),
        "asyncCatch cancelable receives cancel signal" -> forAll(laws.asyncCatchCancelableReceivesCancelSignal[A] _),
        "start then join is identity" -> forAll(laws.startJoinIsIdentity[A] _),
        "join is idempotent" -> forAll(laws.joinIsIdempotent[A] _),
        "start.flatMap(_.cancel) is unit" -> forAll(laws.startCancelIsUnit[A] _),
        "uncancelable mirrors source" -> forAll(laws.uncancelableMirrorsSource[A] _),
        "uncancelable prevents cancelation" -> forAll(laws.uncancelablePreventsCancelation[A] _),
        "race mirrors left winner" -> forAll(laws.raceMirrorsLeftWinner[A] _),
        "race mirrors right winner" -> forAll(laws.raceMirrorsRightWinner[A] _),
        "race cancels loser" -> forAll(laws.raceCancelsLoser[A, B] _),
        "race cancels both" -> forAll(laws.raceCancelsBoth[A, B, C] _),
        "racePair mirrors left winner" -> forAll(laws.racePairMirrorsLeftWinner[A] _),
        "racePair mirrors right winner" -> forAll(laws.racePairMirrorsRightWinner[B] _),
        "racePair cancels both" -> forAll(laws.racePairCancelsBoth[A, B, C] _),
        "racePair can join left" -> forAll(laws.racePairCanJoinLeft[A] _),
        "racePair can join right" -> forAll(laws.racePairCanJoinRight[A] _))
    }
  }
}

object UConcurrentTests {
  def apply[F[_]: UConcurrent]: UConcurrentTests[F] = new UConcurrentTests[F] {
    def laws: UConcurrentLaws[F] = UConcurrentLaws[F]
  }
}
