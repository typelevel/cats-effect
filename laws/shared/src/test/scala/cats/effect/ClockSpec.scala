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

import cats.data.ContT
import cats.{Eq, Show}
import cats.effect.testkit.{freeEval, FreeSyncGenerators}, freeEval._
import cats.syntax.all._
import cats.laws.discipline.arbitrary._
import cats.effect.laws.ClockTests

import org.scalacheck.util.Pretty

import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

import org.typelevel.discipline.specs2.mutable.Discipline

class ClockSpec extends Specification with Discipline with ScalaCheck with BaseSpec {
  import FreeSyncGenerators._

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

  implicit val eqThrowable: Eq[Throwable] =
    Eq.fromUniversalEquals

  // we only need to test the ones that *aren't* also Sync

  checkAll(
    "ContT[FreeEitherSync, Int, *]",
    ClockTests[ContT[FreeEitherSync, Int, *]].clock[Int, Int, Int])
}
