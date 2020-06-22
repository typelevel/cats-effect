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
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._, Prop.forAll

trait SyncManagedTests[R[_[_], _], F[_]] extends SyncTests[R[F, *]] with RegionTests[R, F, Throwable] {

  val laws: SyncManagedLaws[R, F]

  def syncManaged[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
    implicit
      ArbRFA: Arbitrary[R[F, A]],
      ArbFA: Arbitrary[F[A]],
      ArbRFB: Arbitrary[R[F, B]],
      ArbFB: Arbitrary[F[B]],
      ArbRFC: Arbitrary[R[F, C]],
      ArbFC: Arbitrary[F[C]],
      ArbRFU: Arbitrary[R[F, Unit]],
      ArbFU: Arbitrary[F[Unit]],
      ArbRFAtoB: Arbitrary[R[F, A => B]],
      ArbRFBtoC: Arbitrary[R[F, B => C]],
      ArbE: Arbitrary[Throwable],
      CogenA: Cogen[A],
      CogenB: Cogen[B],
      CogenC: Cogen[C],
      CogenE: Cogen[Throwable],
      CogenCaseA: Cogen[Either[Throwable, _]],
      EqRFA: Eq[R[F, A]],
      EqRFB: Eq[R[F, B]],
      EqRFC: Eq[R[F, C]],
      EqFU: Eq[F[Unit]],
      EqE: Eq[Throwable],
      EqRFEitherEU: Eq[R[F, Either[Throwable, Unit]]],
      EqRFEitherEA: Eq[R[F, Either[Throwable, A]]],
      EqRFABC: Eq[R[F, (A, B, C)]],
      EqRFInt: Eq[R[F, Int]],
      EqRFUnit: Eq[R[F, Unit]],
      exec: R[F, Boolean] => Prop,
      iso: Isomorphisms[R[F, *]])
      : RuleSet = {

    new RuleSet {
      val name = "syncManaged"
      val bases = Nil
      val parents = Seq(sync[A, B, C], region[A, B, C])

      val props = Seq("roundTrip" -> forAll(laws.roundTrip[A] _))
    }
  }
}

object SyncManagedTests {
  def apply[R[_[_], _], F[_]](
    implicit
      F0: SyncManaged[R, F],
      B: Bracket[F, Throwable] { type Case[A] = Either[Throwable, A] })
      : SyncManagedTests[R, F] = new SyncManagedTests[R, F] {
    val laws = SyncManagedLaws[R, F]
  }
}
