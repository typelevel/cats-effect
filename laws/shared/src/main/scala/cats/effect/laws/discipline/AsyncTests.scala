/*
 * Copyright 2017 Daniel Spiewak
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
package effect

import cats.data._
import cats.laws.discipline._
import cats.laws.discipline.CartesianTests.Isomorphisms

import org.scalacheck._, Prop.forAll

trait AsyncTests[F[_]] extends MonadErrorTests[F, Throwable] {
  def laws: AsyncLaws[F]

  def async[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
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
      EqEitherTFTA: Eq[EitherT[F, Throwable, A]],
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      iso: Isomorphisms[F]): RuleSet = {
    new RuleSet {
      val name = "async"
      val bases = Nil
      val parents = Seq(monadError[A, B, C])
      val props = Seq(
        "right is pure" -> forAll(laws.asyncRightIsPure[A] _),
        "left is raiseError" -> forAll(laws.asyncLeftIsRaiseError[A] _),
        "thrown in register is raiseError" -> forAll(laws.thrownInRegisterIsRaiseError[A] _))
    }
  }
}

object AsyncTests {
  def apply[F[_]: Async]: AsyncTests[F] = new AsyncTests[F] {
    def laws = AsyncLaws[F]
  }
}
