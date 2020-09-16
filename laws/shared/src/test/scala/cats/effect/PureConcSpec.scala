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

import cats.Show
//import cats.laws.discipline.{AlignTests, ParallelTests}
import cats.laws.discipline.arbitrary._
import cats.implicits._
//import cats.effect.kernel.ParallelF
import cats.effect.laws.GenTemporalTests
import cats.effect.testkit._
import cats.effect.testkit.TimeT._
import cats.effect.testkit.{pure, PureConcGenerators}, pure._

// import org.scalacheck.rng.Seed
import org.scalacheck.util.Pretty
import org.scalacheck.Prop

import org.specs2.ScalaCheck
// import org.specs2.scalacheck.Parameters
import org.specs2.mutable._

import scala.concurrent.duration._

import org.typelevel.discipline.specs2.mutable.Discipline

class PureConcSpec extends Specification with Discipline with ScalaCheck {
//  import ParallelFGenerators._
  import PureConcGenerators._

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

  implicit def exec(fb: TimeT[PureConc[Int, *], Boolean]): Prop =
    Prop(pure.run(TimeT.run(fb)).fold(false, _ => false, _.getOrElse(false)))

  checkAll(
    "TimeT[PureConc]",
    GenTemporalTests[TimeT[PureConc[Int, *], *], Int].temporal[Int, Int, Int](10.millis)
  ) /*(Parameters(seed = Some(Seed.fromBase64("OjD4TDlPxwCr-K-gZb-xyBOGeWMKx210V24VVhsJBLI=").get)))*/

//  checkAll("PureConc", ParallelTests[PureConc[Int, *]].parallel[Int, Int])

//  checkAll(
//    "ParallelF[PureConc]",
//    AlignTests[ParallelF[PureConc[Int, *], *]].align[Int, Int, Int, Int])
}
