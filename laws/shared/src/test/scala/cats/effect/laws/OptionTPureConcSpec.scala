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
package laws

import cats.Applicative
import cats.data.OptionT
import cats.effect.kernel.Outcome
import cats.effect.kernel.syntax.all._
import cats.effect.kernel.testkit.{pure, OutcomeGenerators, PureConcGenerators, TimeT}
import cats.effect.kernel.testkit.TimeT._
import cats.effect.kernel.testkit.pure._
import cats.laws.discipline.arbitrary._

import org.scalacheck.Prop
import org.specs2.mutable._
import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class OptionTPureConcSpec extends Specification with Discipline with BaseSpec {
  import PureConcGenerators._
  import OutcomeGenerators._

  implicit def exec(sbool: OptionT[TimeT[PureConc[Int, *], *], Boolean]): Prop =
    Prop(
      pure
        .run(TimeT.run(sbool.value))
        .fold(
          false,
          _ => false,
          bO => bO.flatten.fold(false)(_ => true)
        ))

  "optiont bracket" should {
    "forward completed zeros on to the handler" in {
      var observed = false

      val test = OptionT.none[PureConc[Int, *], Unit] guaranteeCase {
        case Outcome.Succeeded(fa) =>
          observed = true

          OptionT(fa.value.map(_ must beNone).map(_ => None))

        case _ => Applicative[OptionT[PureConc[Int, *], *]].unit
      }

      pure.run(test.value) must beLike {
        case Outcome.Succeeded(Some(None)) => ok
        case _ => ko
      }

      observed must beTrue
    }
  }

  checkAll(
    "OptionT[TimeT[PureConc]]",
    GenTemporalTests[OptionT[TimeT[PureConc[Int, *], *], *], Int]
      .temporal[Int, Int, Int](10.millis)
  )
}
