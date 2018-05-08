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
import org.scalacheck.Prop.forAll
import org.scalacheck.{Arbitrary, Cogen}

trait UAsyncTests[F[_]] extends USyncTests[F] {
  def laws: UAsyncLaws[F]

  def uasync[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
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
      val name = "uasync"
      val bases = Nil
      val parents = Seq(usync[A, B, C])


      val props = Seq(
        "async left is pure left" -> forAll(laws.asyncLeftIsPureLeft[A] _),
        "async right is pure right" -> forAll(laws.asyncRightIsPureRight[A] _),
        "repeated asyncCatch evaluation is not memoized" -> forAll(laws.repeatedAsyncCatchEvaluationNotMemoized[A] _),
        "repeated callbacks are ignored" -> forAll(laws.repeatedCallbackIgnored[A] _))
    }
  }
}

object UAsyncTests {
  def apply[F[_]: UAsync]: UAsyncTests[F] = new UAsyncTests[F] {
    def laws = UAsyncLaws[F]
  }
}
