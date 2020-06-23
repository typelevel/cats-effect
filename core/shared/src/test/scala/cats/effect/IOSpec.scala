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

import cats.{Eq, Show}
import cats.effect.testkit.TestContext
import cats.implicits._

import org.specs2.mutable.Specification
import org.specs2.matcher.{Matcher, MatchersImplicits}, MatchersImplicits._

class IOSpec extends Specification {

  "io monad" should {
    "produce a pure value when run" in {
      IO.pure(42) must completeAs(42)
    }

    "suspend a side-effect without memoizing" in {
      var i = 42

      val ioa = IO {
        i += 1
        i
      }

      ioa must completeAs(43)
      ioa must completeAs(44)
    }

    "capture errors in suspensions" in {
      case object TestException extends RuntimeException
      IO(throw TestException) must failAs(TestException)
    }

    "map results to a new type" in {
      IO.pure(42).map(_.toString) must completeAs("42")
    }

    "flatMap results sequencing both effects" in {
      var i = 0
      IO.pure(42).flatMap(i2 => IO { i = i2 }) must completeAs(())
      i mustEqual 42
    }
  }

  def completeAs[A: Eq: Show](expected: A): Matcher[IO[A]] =
    tickTo(Some(Right(expected)))

  def failAs(expected: Throwable): Matcher[IO[Unit]] =
    tickTo[Unit](Some(Left(expected)))

  def nonTerminate: Matcher[IO[Unit]] =
    tickTo[Unit](None)

  def tickTo[A: Eq: Show](expected: Option[Either[Throwable, A]]): Matcher[IO[A]] = { (ioa: IO[A]) =>
    implicit val st = Show.fromToString[Throwable]
    implicit val et = Eq.fromUniversalEquals[Throwable]

    val ctx = TestContext()
    var results: Option[Either[Throwable, A]] = None
    ioa.unsafeRunAsync(ctx)(e => results = Some(e))
    ctx.tick()

    (results eqv expected, s"${expected.show} !== ${results.show}")
  }
}
