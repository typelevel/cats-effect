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
import cats.data.EitherT
import cats.effect.kernel.SyncEffect
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._, Prop.forAll

trait SyncEffectTests[F[_]] extends SyncTests[F] {

  val laws: SyncEffectLaws[F]

  def syncEffect[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
      implicit ArbFA: Arbitrary[F[A]],
      ArbFB: Arbitrary[F[B]],
      ArbFC: Arbitrary[F[C]],
      ArbFU: Arbitrary[F[Unit]],
      ArbFAtoB: Arbitrary[F[A => B]],
      ArbFBtoC: Arbitrary[F[B => C]],
      ArbE: Arbitrary[Throwable],
      CogenA: Cogen[A],
      CogenB: Cogen[B],
      CogenC: Cogen[C],
      CogenE: Cogen[Throwable],
      EqFA: Eq[F[A]],
      EqFB: Eq[F[B]],
      EqFC: Eq[F[C]],
      EqE: Eq[Throwable],
      EqFEitherEU: Eq[F[Either[Throwable, Unit]]],
      EqFEitherEA: Eq[F[Either[Throwable, A]]],
      EqEitherTFEA: Eq[EitherT[F, Throwable, A]],
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      exec: F[Boolean] => Prop,
      iso: Isomorphisms[F]): RuleSet = {

    new RuleSet {
      val name = "syncEffect"
      val bases = Nil
      val parents = Seq(sync[A, B, C])

      val props = Seq("roundTrip" -> forAll(laws.roundTrip[A] _))
    }
  }
}

object SyncEffectTests {
  def apply[F[_]](implicit F0: SyncEffect[F]): SyncEffectTests[F] =
    new SyncEffectTests[F] {
      val laws = SyncEffectLaws[F]
    }
}
