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

import cats.effect.implicits._
import cats.kernel.Eq
import cats.laws.discipline.{AlignTests, CommutativeApplicativeTests, ParallelTests}
import cats.laws.discipline.arbitrary._
import cats.syntax.all._

import org.typelevel.discipline.specs2.mutable.Discipline

class IOParSpec extends BaseSpec with Discipline {

  // we just need this because of the laws testing, since the prop runs can interfere with each other
  sequential

  // an alley-eq
  implicit override def eqIOA[A: Eq](implicit ticker: Ticker): Eq[IO[A]] = { (x, y) =>
    import Outcome._
    (unsafeRun(x), unsafeRun(y)) match {
      case (Succeeded(Some(a)), Succeeded(Some(b))) => a eqv b
      case (Succeeded(Some(_)), _) | (_, Succeeded(Some(_))) => false
      case _ => true
    }
  }

  {
    implicit val ticker = Ticker()

    checkAll(
      "IO.Par",
      ParallelTests[IO, IO.Par].parallel[Int, Int]
    )
  }

  {
    implicit val ticker = Ticker()

    checkAll(
      "IO.Par",
      CommutativeApplicativeTests[IO.Par].commutativeApplicative[Int, Int, Int]
    )
  }

  {
    implicit val ticker = Ticker()

    checkAll(
      "IO.Par",
      AlignTests[IO.Par].align[Int, Int, Int, Int]
    )
  }

}
