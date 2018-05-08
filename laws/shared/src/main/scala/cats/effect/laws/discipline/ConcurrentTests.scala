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

import cats.data._
import cats.laws.discipline._
import cats.laws.discipline.SemigroupalTests.Isomorphisms
import org.scalacheck._, Prop.forAll

trait ConcurrentTests[F[_]] extends AsyncTests[F] with UConcurrentTests[F] {
  def laws: ConcurrentLaws[F]

  def concurrent[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
    implicit
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
    params: Parameters): RuleSet = {

    new RuleSet {
      val name = "concurrent"
      val bases = Nil
      val parents = Seq(async[A, B, C], uconcurrent[A, B, C])
      val props = Seq(
        "async cancelable coherence" -> forAll(laws.asyncCancelableCoherence[A] _),
        "async cancelable receives cancel signal" -> forAll(laws.asyncCancelableReceivesCancelSignal[A] _),
        "bracket release is called on cancel" -> forAll(laws.cancelOnBracketReleases[A, B] _),
        "onCancelRaiseError mirrors source" -> forAll(laws.onCancelRaiseErrorMirrorsSource[A] _),
        "onCancelRaiseError terminates on cancel" -> forAll(laws.onCancelRaiseErrorTerminatesOnCancel[A] _),
        "onCancelRaiseError can cancel source" -> forAll(laws.onCancelRaiseErrorCanCancelSource[A] _),
        "racePair cancels loser" -> forAll(laws.racePairCancelsLoser[A, B] _))
    }
  }
}

object ConcurrentTests {
  def apply[F[_]: Concurrent]: ConcurrentTests[F] = new ConcurrentTests[F] {
    def laws = ConcurrentLaws[F]
  }
}
