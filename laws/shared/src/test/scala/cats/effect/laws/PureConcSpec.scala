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

import cats.Eval
import cats.effect.kernel.GenSpawn
import cats.effect.kernel.testkit.{pure, OutcomeGenerators, PureConcGenerators, TimeT}, pure._
import TimeT._
import cats.laws.discipline.arbitrary._
import cats.syntax.eq._

import org.scalacheck.Prop, Prop.forAll

import org.specs2.ScalaCheck
import org.specs2.mutable._

import scala.concurrent.duration._

import org.typelevel.discipline.specs2.mutable.Discipline

class PureConcSpec extends Specification with Discipline with ScalaCheck with BaseSpec {
  import PureConcGenerators._
  import OutcomeGenerators._

  "PureConc" should {
    "respect the both and bothEval correspondence" in {
      def remainder3(n: Int): Int = {
        val r = n % 3
        if (r < 0) -r else r
      }

      def wrapEval(n: Int)(fb: PureConc[Int, Int]): Eval[PureConc[Int, Int]] =
        remainder3(n) match {
          case 0 => Eval.always(fb)
          case 1 => Eval.later(fb)
          case 2 => Eval.now(fb)
        }

      forAll { (fa: PureConc[Int, Int], fb: PureConc[Int, Int], n: Int) =>
        val left =
          GenSpawn[PureConc[Int, *]].both(fa, fb)

        val right =
          GenSpawn[PureConc[Int, *]].bothEval(fa, wrapEval(n)(fb))

        val result = pure.run(left) eqv pure.run(right.value)

        result must beTrue
      }
    }
  }

  implicit def exec(fb: TimeT[PureConc[Int, *], Boolean]): Prop =
    Prop(pure.run(TimeT.run(fb)).fold(false, _ => false, _.getOrElse(false)))

  checkAll(
    "TimeT[PureConc]",
    GenTemporalTests[TimeT[PureConc[Int, *], *], Int].temporal[Int, Int, Int](10.millis)
  )
}
