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

import cats.data.IorT
import cats.effect.kernel.testkit.{pure, OutcomeGenerators, PureConcGenerators, TimeT}
import cats.effect.kernel.testkit.TimeT._
import cats.effect.kernel.testkit.pure._
import cats.laws.discipline.arbitrary._

import org.scalacheck.Prop
import org.specs2.mutable._
import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class IorTPureConcSpec extends Specification with Discipline with BaseSpec {
  import PureConcGenerators._
  import OutcomeGenerators._

  implicit def exec[L](sbool: IorT[TimeT[PureConc[Int, *], *], L, Boolean]): Prop =
    Prop(
      pure
        .run(TimeT.run(sbool.value))
        .fold(
          false,
          _ => false,
          iO => iO.fold(false)(i => i.fold(_ => false, _ => true, (_, _) => false)))
    )

  checkAll(
    "IorT[PureConc]",
    GenTemporalTests[IorT[TimeT[PureConc[Int, *], *], Int, *], Int]
      .temporal[Int, Int, Int](10.millis)
  )
}
