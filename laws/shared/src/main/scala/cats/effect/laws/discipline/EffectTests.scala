/*
 * Copyright 2017 Typelevel
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

import cats.instances.all._
import cats.laws.discipline._
import cats.laws.discipline.CartesianTests.Isomorphisms

import org.scalacheck._, Prop.forAll

trait EffectTests[F[_]] extends AsyncTests[F] with SyncTests[F] with EffectTestsPlatform {
  def laws: EffectLaws[F]

  def effect[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
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
      CogenT: Cogen[Throwable],
      EqFA: Eq[F[A]],
      EqFB: Eq[F[B]],
      EqFC: Eq[F[C]],
      EqT: Eq[Throwable],
      EqFEitherTU: Eq[F[Either[Throwable, Unit]]],
      EqFEitherTA: Eq[F[Either[Throwable, A]]],
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      EqIOA: Eq[IO[A]],
      EqIOEitherTA: Eq[IO[Either[Throwable, A]]],
      EqIOEitherEitherTA: Eq[IO[Either[Throwable, Either[Throwable, A]]]],
      iso: Isomorphisms[F]): RuleSet = {
    new RuleSet {
      val name = "effect"
      val bases = Nil
      val parents = Seq(async[A, B, C], sync[A, B, C])

      val baseProps = Seq(
        "runAsync pure produces right IO" -> forAll(laws.runAsyncPureProducesRightIO[A] _),
        "runAsync raiseError produces left IO" -> forAll(laws.runAsyncRaiseErrorProducesLeftIO[A] _),
        "repeated callback ignored" -> forAll(laws.repeatedCallbackIgnored[A] _),
        "propagate errors through bind (suspend)" -> forAll(laws.propagateErrorsThroughBindSuspend[A] _),
        "propagate errors through bind (async)" -> forAll(laws.propagateErrorsThroughBindSuspend[A] _))

      val jvmProps = Seq(
        "stack-safe on left-associated binds" -> Prop.lzy(laws.stackSafetyOnRepeatedLeftBinds),
        "stack-safe on right-associated binds" -> Prop.lzy(laws.stackSafetyOnRepeatedRightBinds),
        "stack-safe on repeated attempts" -> Prop.lzy(laws.stackSafetyOnRepeatedAttempts))

      val jsProps = Seq.empty

      val props = baseProps ++ (if (isJVM) jvmProps else jsProps)
    }
  }
}

object EffectTests {
  def apply[F[_]: Effect]: EffectTests[F] = new EffectTests[F] {
    def laws = EffectLaws[F]
  }
}
