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

import cats.data.EitherT
import cats.effect.kernel.testkit.{pure, OutcomeGenerators, PureConcGenerators, TimeT}
import cats.effect.kernel.testkit.TimeT._
import cats.effect.kernel.testkit.pure._
import cats.laws.discipline.arbitrary._

import org.scalacheck.Prop
import org.specs2.mutable._
import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class EitherTPureConcSpec extends Specification with Discipline with BaseSpec {
  import PureConcGenerators._
  import OutcomeGenerators._

  implicit def exec[E](sbool: EitherT[TimeT[PureConc[Int, *], *], E, Boolean]): Prop =
    Prop(
      pure
        .run(TimeT.run(sbool.value))
        .fold(false, _ => false, bO => bO.fold(false)(e => e.fold(_ => false, b => b))))

  checkAll(
    "EitherT[TimeT[PureConc]]",
    GenTemporalTests[EitherT[TimeT[PureConc[Int, *], *], Int, *], Int]
      .temporal[Int, Int, Int](10.millis)
  )
}
