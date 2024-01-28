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

import cats.{Eq, FlatMap}
import cats.data.StateT
import cats.effect.kernel.testkit.PureConcGenerators
import cats.effect.kernel.testkit.pure._
import cats.laws.discipline.MiniInt
import cats.laws.discipline.arbitrary._
import cats.laws.discipline.eq._

import org.specs2.mutable._
import org.specs2.scalacheck.Parameters
import org.typelevel.discipline.specs2.mutable.Discipline

class StateTPureConcSpec extends Specification with Discipline with BaseSpec {
  import PureConcGenerators._

  implicit def stateTEq[F[_]: FlatMap, S, A](
      implicit ev: Eq[S => F[(S, A)]]): Eq[StateT[F, S, A]] =
    Eq.by[StateT[F, S, A], S => F[(S, A)]](_.run)

  checkAll(
    "StateT[PureConc]",
    MonadCancelTests[StateT[PureConc[Int, *], MiniInt, *], Int].monadCancel[Int, Int, Int]
  )(Parameters(minTestsOk =
    25 /*, seed = Some(Seed.fromBase64("Ky43MND8m5h-10MZTckMFFAW6ea2pXWkFDE2A7ddtML=").get*/ ))
}
