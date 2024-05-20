/*
 * Copyright 2020-2024 Typelevel
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
import cats.effect.kernel.Sync
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._
import org.scalacheck.Prop.forAll
import org.typelevel.discipline.Laws

trait SyncTests[F[_]]
    extends MonadCancelTests[F, Throwable]
    with ClockTests[F]
    with UniqueTests[F] {

  val laws: SyncLaws[F]

  def sync[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
      implicit ArbFA: Arbitrary[F[A]],
      ArbFB: Arbitrary[F[B]],
      ArbFC: Arbitrary[F[C]],
      ArbFU: Arbitrary[F[Unit]],
      ArbFAtoB: Arbitrary[F[A => B]],
      ArbFBtoC: Arbitrary[F[B => C]],
      ArbE: Arbitrary[Throwable],
      ArbST: Arbitrary[Sync.Type],
      CogenA: Cogen[A],
      CogenB: Cogen[B],
      CogenC: Cogen[C],
      CogenE: Cogen[Throwable],
      EqFA: Eq[F[A]],
      EqFB: Eq[F[B]],
      EqFC: Eq[F[C]],
      EqFU: Eq[F[Unit]],
      EqE: Eq[Throwable],
      EqFEitherEU: Eq[F[Either[Throwable, Unit]]],
      EqFEitherEA: Eq[F[Either[Throwable, A]]],
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      exec: F[Boolean] => Prop,
      iso: Isomorphisms[F]): RuleSet = {

    new RuleSet {
      val name = "sync"
      val bases: Seq[(String, Laws#RuleSet)] = Nil
      val parents = Seq(
        monadCancel[A, B, C](
          implicitly[Arbitrary[A]],
          implicitly[Eq[A]],
          implicitly[Arbitrary[B]],
          implicitly[Eq[B]],
          implicitly[Arbitrary[C]],
          implicitly[Eq[C]],
          ArbFA,
          ArbFB,
          ArbFC,
          ArbFU,
          ArbFAtoB,
          ArbFBtoC,
          ArbE,
          CogenA,
          CogenB,
          CogenC,
          CogenE,
          EqFA,
          EqFB,
          EqFC,
          EqFU,
          EqE,
          EqFEitherEU,
          EqFEitherEA,
          EqFABC,
          EqFInt,
          iso
        ),
        clock,
        unique
      )

      val props = Seq(
        "suspend value is pure" -> forAll(laws.suspendValueIsPure[A] _),
        "suspend throw is raiseError" -> forAll(laws.suspendThrowIsRaiseError[A] _),
        "unsequenced suspend is no-op" -> forAll(laws.unsequencedSuspendIsNoop[A] _),
        "repeated suspend is not memoized" -> forAll(laws.repeatedSuspendNotMemoized[A] _)
      )
    }
  }
}

object SyncTests {
  def apply[F[_]](implicit F0: Sync[F]): SyncTests[F] =
    new SyncTests[F] {
      val laws = SyncLaws[F]
    }
}
