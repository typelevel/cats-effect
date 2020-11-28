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

import cats.syntax.all._
import cats.effect.implicits._

import org.scalacheck.Arbitrary.arbitrary

import org.specs2.ScalaCheck

import org.typelevel.discipline.specs2.mutable.Discipline

import org.scalacheck.Gen
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

//We allow these tests to have a longer timeout than IOSpec as they run lots of iterations
class IOPropSpec extends IOPlatformSpecification with Discipline with ScalaCheck with BaseSpec {
  outer =>

  override def executionTimeout: FiniteDuration = 30.second

  "io monad" should {

    "parTraverseN" should {
      "give the same result as parTraverse" in realProp(
        Gen.posNum[Int].flatMap(n => arbitrary[List[Int]].map(n -> _))) {
        case (n, l) =>
          val f: Int => IO[Int] = n => IO.pure(n + 1)

          l.parTraverse(f).flatMap { expected =>
            l.parTraverseN(n)(f).mustEqual(expected)
          }
      }
    }

    "parSequenceN" should {
      "give the same result as parSequence" in realProp(
        Gen.posNum[Int].flatMap(n => arbitrary[List[Int]].map(n -> _))) {
        case (n, l) =>
          l.map(IO.pure(_)).parSequence.flatMap { expected =>
            l.map(IO.pure(_)).parSequenceN(n).mustEqual(expected)
          }
      }

    }
  }

}
