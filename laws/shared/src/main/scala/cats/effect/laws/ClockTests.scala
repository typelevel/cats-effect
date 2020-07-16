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
import cats.effect.kernel.Clock
import cats.laws.discipline._
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._

trait ClockTests[F[_]] extends ApplicativeTests[F] {

  val laws: ClockLaws[F]

  def clock[A: Arbitrary, B: Arbitrary, C: Arbitrary](
      implicit ArbFA: Arbitrary[F[A]],
      ArbFB: Arbitrary[F[B]],
      ArbFC: Arbitrary[F[C]],
      ArbFAtoB: Arbitrary[F[A => B]],
      ArbFBtoC: Arbitrary[F[B => C]],
      CogenA: Cogen[A],
      CogenB: Cogen[B],
      CogenC: Cogen[C],
      EqFA: Eq[F[A]],
      EqFB: Eq[F[B]],
      EqFC: Eq[F[C]],
      EqFABC: Eq[F[(A, B, C)]],
      exec: F[Boolean] => Prop,
      iso: Isomorphisms[F]): RuleSet = {

    new RuleSet {
      val name = "clock"
      val bases = Nil
      val parents = Seq(applicative[A, B, C])

      val props = Seq("monotonicity" -> laws.monotonicity)
    }
  }
}

object ClockTests {
  def apply[F[_]](implicit F0: Clock[F]): ClockTests[F] =
    new ClockTests[F] {
      val laws = ClockLaws[F]
    }
}
