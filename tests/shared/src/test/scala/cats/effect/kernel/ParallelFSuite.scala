/*
 * Copyright 2020-2025 Typelevel
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

import org.scalacheck.Test

import munit.DisciplineSuite

class ParallelFSuite extends BaseSuite with DisciplineSuite with DetectPlatform {

  override protected def scalaCheckTestParameters: Test.Parameters = {
    if (isNative) super.scalaCheckTestParameters.withMinSuccessfulTests(5)
    else super.scalaCheckTestParameters.withMinSuccessfulTests(100)
  }

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
