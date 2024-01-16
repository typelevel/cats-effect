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

import cats.{Eq, Eval}
import cats.data.StateT
import cats.effect.kernel.testkit.{FreeSyncGenerators, SyncTypeGenerators}
import cats.effect.kernel.testkit.freeEval.{syncForFreeT, FreeEitherSync}
import cats.free.FreeT
import cats.laws.discipline.MiniInt
import cats.laws.discipline.arbitrary._

import munit.DisciplineSuite

class StateTFreeSyncSuite extends DisciplineSuite with BaseSuite with LowPriorityImplicits {
  import FreeSyncGenerators._
  import SyncTypeGenerators._

  implicit val scala_2_12_is_buggy
      : Eq[FreeT[Eval, Either[Throwable, *], Either[Int, Either[Throwable, Int]]]] =
    eqFreeSync[Either[Throwable, *], Either[Int, Either[Throwable, Int]]]

  checkAll(
    "StateT[FreeEitherSync]",
    SyncTests[StateT[FreeEitherSync, MiniInt, *]].sync[Int, Int, Int])
}
