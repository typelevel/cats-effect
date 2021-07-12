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

import cats.effect.kernel.{MonadCancel, Outcome}
import cats.effect.kernel.testkit.{pure, OutcomeGenerators, PureConcGenerators, TimeT}, pure._,
TimeT._
import cats.laws.discipline.arbitrary._
import cats.syntax.all._

import org.scalacheck.Prop

import org.specs2.ScalaCheck
import org.specs2.mutable._

import scala.concurrent.duration._

import org.typelevel.discipline.specs2.mutable.Discipline

class PureConcSpec extends Specification with Discipline with ScalaCheck with BaseSpec {
  import PureConcGenerators._
  import OutcomeGenerators._

  "do the cancelation thingy" >> {
    val F = MonadCancel[PureConc[Int, *]]

    val results = pure.run(
      F.start(F.guaranteeCase(F.uncancelable(_ => F.canceled))(oc => F.unit.map(_ => println(oc)))).flatMap(_.join))

    results mustEqual Outcome.Succeeded(Some(()))
  }

  implicit def exec(fb: TimeT[PureConc[Int, *], Boolean]): Prop =
    Prop(pure.run(TimeT.run(fb)).fold(false, _ => false, _.getOrElse(false)))

  checkAll(
    "TimeT[PureConc]",
    GenTemporalTests[TimeT[PureConc[Int, *], *], Int].temporal[Int, Int, Int](10.millis)
  )
}
