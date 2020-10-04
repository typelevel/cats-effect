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
package laws

import cats.data.WriterT
import cats.laws.discipline.arbitrary._
import cats.effect.testkit._
import cats.effect.testkit.TimeT._
import cats.effect.testkit.{pure, PureConcGenerators}, pure._

import org.scalacheck.Prop

import org.specs2.ScalaCheck
import org.specs2.mutable._

import scala.concurrent.duration._

import org.typelevel.discipline.specs2.mutable.Discipline

class WriterTPureConcSpec extends Specification with Discipline with ScalaCheck with BaseSpec {
  import PureConcGenerators._

  implicit def exec[S](sbool: WriterT[TimeT[PureConc[Int, *], *], S, Boolean]): Prop =
    Prop(
      pure
        .run(TimeT.run(sbool.run))
        .fold(
          false,
          _ => false,
          pO => pO.fold(false)(p => p._2)
        )
    )

  checkAll(
    "WriterT[PureConc]",
    GenTemporalTests[WriterT[TimeT[PureConc[Int, *], *], Int, *], Int]
      .temporal[Int, Int, Int](10.millis)
  )
}
