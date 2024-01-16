/*
 * Copyright 2020-2023 Typelevel
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

import cats.{Eq, Monad}
import cats.data.ReaderWriterStateT
import cats.effect.kernel.testkit.PureConcGenerators
import cats.effect.kernel.testkit.pure._
import cats.laws.discipline.MiniInt
import cats.laws.discipline.arbitrary._
import cats.laws.discipline.eq._

import munit.DisciplineSuite

class ReaderWriterStateTPureConcSuite extends DisciplineSuite with BaseSuite {
  import PureConcGenerators._

  implicit def rwstEq[F[_]: Monad, E, L, S, A](
      implicit ev: Eq[(E, S) => F[(L, S, A)]]): Eq[ReaderWriterStateT[F, E, L, S, A]] =
    Eq.by[ReaderWriterStateT[F, E, L, S, A], (E, S) => F[(L, S, A)]](_.run)

  override def scalaCheckTestParameters =
    // we need to bound this a little tighter because these tests take FOREVER, especially on scalajs
    super.scalaCheckTestParameters.withMinSuccessfulTests(1)

  checkAll(
    "ReaderWriterStateT[PureConc]",
    MonadCancelTests[ReaderWriterStateT[PureConc[Int, *], MiniInt, Int, MiniInt, *], Int]
      .monadCancel[Int, Int, Int]
  )
}
