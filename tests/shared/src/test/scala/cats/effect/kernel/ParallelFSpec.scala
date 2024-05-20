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
package kernel

import cats.effect.kernel.instances.all._
import cats.effect.kernel.testkit.PureConcGenerators._
import cats.effect.kernel.testkit.pure.{orderForPureConc => _, _}
import cats.kernel.Eq
import cats.laws.discipline.{AlignTests, CommutativeApplicativeTests, ParallelTests}
import cats.laws.discipline.arbitrary.catsLawsCogenForIor
import cats.syntax.all._

import org.specs2.scalacheck._
import org.typelevel.discipline.specs2.mutable.Discipline

class ParallelFSpec extends BaseSpec with Discipline with DetectPlatform {

  implicit val params: Parameters =
    if (isNative)
      Parameters(minTestsOk = 5)
    else
      Parameters(minTestsOk = 100)

  def alleyEq[E, A: Eq]: Eq[PureConc[E, A]] = { (x, y) =>
    import Outcome._
    (run(x), run(y)) match {
      case (Succeeded(Some(a)), Succeeded(Some(b))) => a eqv b
      case (Succeeded(Some(_)), _) | (_, Succeeded(Some(_))) => false
      case _ => true
    }
  }

  implicit def alleyEqUnit[A: Eq]: Eq[PureConc[Unit, A]] = alleyEq[Unit, A]
  implicit def alleyEqThrowable[A: Eq]: Eq[PureConc[Throwable, A]] = alleyEq[Throwable, A]

  checkAll(
    "ParallelF[PureConc]",
    ParallelTests[PureConc[Unit, *], ParallelF[PureConc[Unit, *], *]].parallel[Int, Int])

  checkAll(
    "ParallelF[PureConc]",
    CommutativeApplicativeTests[ParallelF[PureConc[Unit, *], *]]
      .commutativeApplicative[Int, Int, Int])

  checkAll(
    "ParallelF[PureConc]",
    AlignTests[ParallelF[PureConc[Unit, *], *]].align[Int, Int, Int, Int])

  checkAll(
    "ParallelF[Resource[PureConc]]",
    ParallelTests[
      Resource[PureConc[Throwable, *], *],
      ParallelF[Resource[PureConc[Throwable, *], *], *]].parallel[Int, Int])

  checkAll(
    "ParallelF[Resource[PureConc]]",
    CommutativeApplicativeTests[ParallelF[Resource[PureConc[Throwable, *], *], *]]
      .commutativeApplicative[Int, Int, Int])

  checkAll(
    "ParallelF[Resource[PureConc]]",
    AlignTests[ParallelF[Resource[PureConc[Throwable, *], *], *]].align[Int, Int, Int, Int])

}
