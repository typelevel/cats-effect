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
import cats.effect.laws.BracketTests
import cats.implicits._
import cats.laws.discipline.arbitrary._

import org.specs2.mutable.Specification

import org.typelevel.discipline.specs2.mutable.Discipline

class BracketSpec extends Specification with Discipline {

  checkAll(
    "Either[Int, *]",
    BracketTests[Either[Int, *], Int].bracket[Int, Int, Int])

  checkAll(
    "OptionT[Either[Int, *], *]",
    BracketTests[OptionT[Either[Int, *], *], Int].bracket[Int, Int, Int])
}
