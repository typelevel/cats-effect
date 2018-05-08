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

trait ConcurrentEffectTests[F[_]] extends ConcurrentTests[F] with EffectTests[F] {
  def laws: ConcurrentEffectLaws[F]

  def concurrentEffect[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
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
    EqFABC: Eq[F[(A, B, C)]],
    EqFInt: Eq[F[Int]],
    EqIOA: Eq[IO[A]],
    EqIOU: Eq[IO[Unit]],
    EqIOEitherTA: Eq[IO[Either[Throwable, A]]],
    EqIOEitherFAA: Eq[IO[Either[F[A], A]]],
    iso: Isomorphisms[F],
    params: Parameters): RuleSet = {

    new RuleSet {
      val name = "concurrentEffect"
      val bases = Nil
      val parents = Seq(concurrent[A, B, C], effect[A, B, C])
      val props = Seq(
        "runAsync runCancelable coherence" -> forAll(laws.runAsyncRunCancelableCoherence[A] _),
        "runCancelable is synchronous" -> forAll(laws.runCancelableIsSynchronous[A] _),
        "runCancelable start.flatMap(_.cancel) coherence" -> forAll(laws.runCancelableStartCancelCoherence[A] _))
    }
  }
}

object ConcurrentEffectTests {
  def apply[F[_]: ConcurrentEffect]: ConcurrentEffectTests[F] = new ConcurrentEffectTests[F] {
    def laws = ConcurrentEffectLaws[F]
  }
}
