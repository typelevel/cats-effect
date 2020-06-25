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

import cats.{Eq, Order, Show}
import cats.kernel.laws.discipline.GroupTests
import cats.effect.laws.EffectTests
import cats.effect.testkit.{AsyncGenerators, BracketGenerators, OutcomeGenerators, TestContext}
import cats.implicits._

import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}

import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import org.specs2.matcher.{Matcher, MatchersImplicits}, MatchersImplicits._

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.TimeUnit

class IOSpec extends Specification with Discipline with ScalaCheck { outer =>
  import OutcomeGenerators._

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

    "resume value continuation within async" in {
      IO.async[Int](k => IO(k(Right(42))).map(_ => None)) must completeAs(42)
    }

    "resume error continuation within async" in {
      case object TestException extends RuntimeException
      IO.async[Unit](k => IO(k(Left(TestException))).as(None)) must failAs(TestException)
    }

    "map results to a new type" in {
      IO.pure(42).map(_.toString) must completeAs("42")
    }

    "flatMap results sequencing both effects" in {
      var i = 0
      IO.pure(42).flatMap(i2 => IO { i = i2 }) must completeAs(())
      i mustEqual 42
    }

    "raiseError propagates out" in {
      case object TestException extends RuntimeException
      IO.raiseError(TestException).void must failAs(TestException)
    }

    "errors can be handled" in {
      case object TestException extends RuntimeException
      (IO.raiseError(TestException): IO[Unit]).attempt must completeAs(Left(TestException))
    }

    "start and join on a successful fiber" in {
      IO.pure(42).map(_ + 1).start.flatMap(_.join) must completeAs(Outcome.Completed(IO.pure(43)))
    }

    "start and join on a failed fiber" in {
      case object TestException extends RuntimeException
      (IO.raiseError(TestException): IO[Unit]).start.flatMap(_.join) must completeAs(Outcome.Errored(TestException))
    }

    "implement never with non-terminating semantics" in {
      IO.never must nonTerminate
    }

    "start and ignore a non-terminating fiber" in {
      IO.never.start.as(42) must completeAs(42)
    }
  }

  /*{
    implicit val ctx = TestContext()

    checkAll(
      "IO",
      EffectTests[IO].effect[Int, Int, Int](10.millis))

    checkAll(
      "IO[Int]",
      GroupTests[IO[Int]].group)
  }*/

  // TODO organize the below somewhat better

  implicit def cogenIO[A: Cogen]: Cogen[IO[A]] =
    Cogen[Outcome[Option, Throwable, A]].contramap(unsafeRun(_))

  implicit def arbitraryIO[A: Arbitrary: Cogen]: Arbitrary[IO[A]] = {
    val generators =
      new AsyncGenerators[IO] with BracketGenerators[IO, Throwable] {

        val arbitraryE: Arbitrary[Throwable] = implicitly[Arbitrary[Throwable]]

        val cogenE: Cogen[Throwable] = Cogen[Throwable]

        val F: AsyncBracket[IO] = IO.effectForIO

        def cogenCase[B: Cogen]: Cogen[Outcome[IO, Throwable, B]] =
          OutcomeGenerators.cogenOutcome[IO, Throwable, B]

        val arbitraryEC: Arbitrary[ExecutionContext] = outer.arbitraryEC

        val cogenFU: Cogen[IO[Unit]] = cogenIO[Unit]

        // TODO dedup with FreeSyncGenerators
        val arbitraryFD: Arbitrary[FiniteDuration] = {
          import TimeUnit._

          val genTU = Gen.oneOf(NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS)

          Arbitrary {
            genTU flatMap { u =>
              Gen.posNum[Long].map(FiniteDuration(_, u))
            }
          }
        }
      }

    Arbitrary(generators.generators[A])
  }

  implicit lazy val arbitraryEC: Arbitrary[ExecutionContext] =
    Arbitrary(Gen.delay(Gen.const(TestContext())))

  implicit lazy val eqThrowable: Eq[Throwable] =
    Eq.fromUniversalEquals[Throwable]

  implicit lazy val shThrowable: Show[Throwable] =
    Show.fromToString[Throwable]

  implicit lazy val eqEC: Eq[ExecutionContext] =
    Eq.fromUniversalEquals[ExecutionContext]

  implicit lazy val ordIOFD: Order[IO[FiniteDuration]] =
    Order by { ioa =>
      unsafeRun(ioa).fold(
        None,
        _ => None,
        fa => fa)
    }

  implicit def eqIOA[A: Eq]: Eq[IO[A]] =
    Eq.by(unsafeRun(_))

  // feel the rhythm, feel the rhyme...
  implicit def boolRunnings(iob: IO[Boolean]): Prop =
    Prop(unsafeRun(iob).fold(false, _ => false, _.getOrElse(false)))

  def completeAs[A: Eq: Show](expected: A): Matcher[IO[A]] =
    tickTo(Outcome.Completed(Some(expected)))

  def failAs(expected: Throwable): Matcher[IO[Unit]] =
    tickTo[Unit](Outcome.Errored(expected))

  def nonTerminate: Matcher[IO[Unit]] =
    tickTo[Unit](Outcome.Completed(None))

  def tickTo[A: Eq: Show](expected: Outcome[Option, Throwable, A]): Matcher[IO[A]] = { (ioa: IO[A]) =>
    val oc = unsafeRun(ioa)
    (oc eqv expected, s"${oc.show} !== ${expected.show}")
  }

  def unsafeRun[A](ioa: IO[A]): Outcome[Option, Throwable, A] = {
    val ctx = TestContext()

    var results: Outcome[Option, Throwable, A] = Outcome.Completed(None)
    ioa.unsafeRunAsync(ctx) {
      case Left(t) => results = Outcome.Errored(t)
      case Right(a) => results = Outcome.Completed(Some(a))
    }

    ctx.tick()

    results
  }
}
