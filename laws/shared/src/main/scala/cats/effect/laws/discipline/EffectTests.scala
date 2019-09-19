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

import cats.laws.discipline._
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._, Prop.forAll

trait EffectTests[F[_]] extends AsyncTests[F] {
  def laws: EffectLaws[F]

  def effect[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](implicit
                                                                   ArbFA: Arbitrary[F[A]],
                                                                   ArbFB: Arbitrary[F[B]],
                                                                   ArbFC: Arbitrary[F[C]],
                                                                   ArbFU: Arbitrary[F[Unit]],
                                                                   ArbFAtoB: Arbitrary[F[A => B]],
                                                                   ArbFBtoC: Arbitrary[F[B => C]],
                                                                   ArbT: Arbitrary[Throwable],
                                                                   ArgIOA: Arbitrary[IO[A]],
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
                                                                   EqIOU: Eq[IO[Unit]],
                                                                   EqIOEitherTA: Eq[IO[Either[Throwable, A]]],
                                                                   EqIOA: Eq[IO[A]],
                                                                   iso: Isomorphisms[F],
                                                                   params: Parameters): RuleSet =
    new RuleSet {
      val name = "effect"
      val bases = Nil
      val parents = Seq(async[A, B, C])
      val props = Seq(
        "runAsync pure produces right IO" -> forAll(laws.runAsyncPureProducesRightIO[A] _),
        "runAsync raiseError produces left IO" -> forAll(laws.runAsyncRaiseErrorProducesLeftIO[A] _),
        "runAsync ignores error in handler" -> forAll(laws.runAsyncIgnoresErrorInHandler[A] _),
        "repeated callback ignored" -> forAll(laws.repeatedCallbackIgnored[A] _),
        "toIO is the inverse of liftIO" -> forAll(laws.toIOinverseOfLiftIO[A] _),
        "toIO is consistent with runAsync" -> forAll(laws.toIORunAsyncConsistency[A] _),
        "toIO stack safety" -> forAll(laws.toIOStackSafety[A](params.stackSafeIterationsCount) _)
      )
    }
}

object EffectTests {
  def apply[F[_]: Effect]: EffectTests[F] = new EffectTests[F] {
    def laws = EffectLaws[F]
  }
}
