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
package kernel

import cats.effect.kernel.instances.all._
import cats.effect.kernel.testkit.PureConcGenerators._
import cats.effect.kernel.testkit.pure._
import cats.laws.discipline.AlignTests
import cats.laws.discipline.CommutativeApplicativeTests
import cats.laws.discipline.ParallelTests
import cats.laws.discipline.arbitrary.catsLawsCogenForIor
import org.typelevel.discipline.specs2.mutable.Discipline

class ParallelFSpec extends BaseSpec with Discipline {

  checkAll(
    "Parallel[F, ParallelF]",
    ParallelTests[PureConc[Int, *], ParallelF[PureConc[Int, *], *]].parallel[Int, Int])

  checkAll(
    "CommutativeApplicative[ParallelF]",
    CommutativeApplicativeTests[ParallelF[PureConc[Int, *], *]]
      .commutativeApplicative[Int, Int, Int])

  checkAll(
    "Align[ParallelF]",
    AlignTests[ParallelF[PureConc[Int, *], *]].align[Int, Int, Int, Int])

}
