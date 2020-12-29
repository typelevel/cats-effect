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

import cats.data.OptionT
import cats.Order
import cats.laws.discipline.arbitrary._
import cats.effect.laws.AsyncTests
import cats.effect.kernel.testkit.{SyncTypeGenerators, TestContext}
import cats.implicits._

import org.scalacheck.Prop

import org.specs2.ScalaCheck

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class OptionTIOSpec
    extends IOPlatformSpecification
    with Discipline
    with ScalaCheck
    with BaseSpec {
  outer =>

  import SyncTypeGenerators._

  // we just need this because of the laws testing, since the prop runs can interfere with each other
  sequential

  implicit def ordOptionTIOFD(implicit ticker: Ticker): Order[OptionT[IO, FiniteDuration]] =
    Order by { ioaO => unsafeRun(ioaO.value).fold(None, _ => None, fa => fa) }

  implicit def execOptionT(sbool: OptionT[IO, Boolean])(implicit ticker: Ticker): Prop =
    Prop(
      unsafeRun(sbool.value).fold(
        false,
        _ => false,
        bO => bO.flatten.fold(false)(b => b)
      ))

  {
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "OptionT[IO]",
      AsyncTests[OptionT[IO, *]].async[Int, Int, Int](10.millis)
    ) /*(Parameters(seed = Some(Seed.fromBase64("XidlR_tu11X7_v51XojzZJsm6EaeU99RAEL9vzbkWBD=").get)))*/
  }

}
