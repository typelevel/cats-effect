/*
 * Copyright 2020-2021 Typelevel
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

import cats.effect.kernel.ParallelF
import cats.effect.kernel.implicits._
import cats.effect.kernel.testkit.{pure, ParallelFGenerators, PureConcGenerators}, pure._
import cats.laws.discipline.CommutativeApplicativeTests
import org.specs2.mutable.Specification
import org.typelevel.discipline.specs2.mutable.Discipline

class PureConcParSpec extends Specification with Discipline with BaseSpec {

  import ParallelFGenerators._
  import PureConcGenerators._

  checkAll(
    "CommutativeApplicative[ParallelF[PureConc]]",
    CommutativeApplicativeTests[ParallelF[PureConc[Int, *], *]]
      .commutativeApplicative[Int, Int, Int])
}
