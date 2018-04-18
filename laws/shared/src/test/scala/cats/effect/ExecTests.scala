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

package cats.effect

import cats.data.{EitherT, NonEmptyList}
import cats.laws.discipline.MonadTests
import cats.kernel.laws.discipline.{MonoidTests, SemigroupTests}
import cats.effect.laws.discipline.SyncTests
import cats.effect.laws.discipline.arbitrary._
import cats.laws.discipline.arbitrary._
import cats.instances.all._

class ExecTests extends BaseTestsSuite {

  checkAll("UExec", MonadTests[UExec].monad[Int, Int, Int])
  checkAll("UExec[String]", MonoidTests[UExec[String]].monoid)
  checkAll("UExec[NonEmptyList[String]]", SemigroupTests[UExec[NonEmptyList[String]]].semigroup)

  checkAll("EitherT[UExec, Throwable, ?]",
    SyncTests[EitherT[UExec.Type, Throwable, ?]].sync[Int, Int, Int])

}
