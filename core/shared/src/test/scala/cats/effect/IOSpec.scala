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
import cats.kernel.laws.discipline.MonoidTests
import cats.effect.laws.EffectTests
import cats.effect.testkit.{AsyncGenerators, BracketGenerators, GenK, OutcomeGenerators, TestContext}
import cats.implicits._

import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}, Prop.forAll
// import org.scalacheck.rng.Seed

import org.specs2.ScalaCheck
// import org.specs2.scalacheck.Parameters
import org.specs2.matcher.Matcher

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.TimeUnit

class IOSpec extends IOPlatformSpecification with Discipline with ScalaCheck with BaseSpec { outer =>
  import OutcomeGenerators._

  sequential

  val ctx = TestContext()

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
      IO.raiseError(TestException).void.flatMap(_ => IO.pure(())) must failAs(TestException)
    }

    "errors can be handled" in {
      case object TestException extends RuntimeException
      IO.raiseError[Unit](TestException).attempt must completeAs(Left(TestException))
    }

    "start and join on a successful fiber" in {
      IO.pure(42).map(_ + 1).start.flatMap(_.join) must completeAs(Outcome.completed[IO, Throwable, Int](IO.pure(43)))
    }

    "start and join on a failed fiber" in {
      case object TestException extends RuntimeException
      (IO.raiseError(TestException): IO[Unit]).start.flatMap(_.join) must completeAs(Outcome.errored[IO, Throwable, Unit](TestException))
    }

    "implement never with non-terminating semantics" in {
      IO.never must nonTerminate
    }

    "start and ignore a non-terminating fiber" in {
      IO.never.start.as(42) must completeAs(42)
    }

    "start a fiber then continue with its results" in {
      IO.pure(42).start.flatMap(_.join) flatMap { oc =>
        oc.fold(IO.pure(0), _ => IO.pure(-1), ioa => ioa)
      } must completeAs(42)
    }

    "continue from the results of an async produced prior to registration" in {
      IO.async[Int](cb => IO(cb(Right(42))).as(None)).map(_ + 2) must completeAs(44)
    }

    "produce a failure when the registration raises an error after callback" in {
      case object TestException extends RuntimeException
      IO.async[Int](cb => IO(cb(Right(42))).flatMap(_ => IO.raiseError(TestException))).void must failAs(TestException)
    }

    "cancel an infinite chain of right-binds" in {
      lazy val infinite: IO[Unit] = IO.unit.flatMap(_ => infinite)
      infinite.start.flatMap(f => f.cancel >> f.join) must completeAs(Outcome.canceled[IO, Throwable, Unit])
    }

    "cancel never" in {
      (IO.never: IO[Unit]).start.flatMap(f => f.cancel >> f.join) must completeAs(Outcome.canceled[IO, Throwable, Unit])
    }

    "cancel never after scheduling" in {
      val ioa = for {
        f <- (IO.never: IO[Unit]).start
        ec <- IO.executionContext
        _ <- IO(ec.asInstanceOf[TestContext].tickAll())
        _ <- f.cancel
        oc <- f.join
      } yield oc

      ioa must completeAs(Outcome.canceled[IO, Throwable, Unit])
    }

    "sequence async cancel token upon cancelation during suspension" in {
      var affected = false

      val target = IO.async[Unit] { _ =>
        IO.pure(Some(IO { affected = true }))
      }

      val ioa = for {
        f <- target.start
        _ <- IO(ctx.tickAll())
        _ <- f.cancel
      } yield ()

      ioa must completeAs(())
      affected must beTrue
    }

    "suppress async cancel token upon cancelation in masked region" in {
      var affected = false

      val target = IO uncancelable { _ =>
        IO.async[Unit] { _ =>
          IO.pure(Some(IO { affected = true }))
        }
      }

      val ioa = for {
        f <- target.start
        _ <- IO(ctx.tickAll())
        _ <- f.cancel
      } yield ()

      ioa must completeAs(())
      affected must beFalse
    }

    "preserve contexts through start" in {
      val ec = ctx.derive()

      val ioa = for {
        f <- IO.executionContext.start.evalOn(ec)
        _ <- IO(ctx.tickAll())
        oc <- f.join
      } yield oc

      ioa must completeAs(Outcome.completed[IO, Throwable, ExecutionContext](IO.pure(ec)))
    }

    "preserve monad identity on async" in {
      val fa = IO.async[Int](cb => IO(cb(Right(42))).as(None))
      fa.flatMap(i => IO.pure(i)) must completeAs(42)
      fa must completeAs(42)
    }

    "preserve monad right identity on uncancelable" in {
      val fa = IO.uncancelable(_ => IO.canceled)
      fa.flatMap(IO.pure(_)) must nonTerminate
      fa must nonTerminate
    }

    "cancel flatMap continuations following a canceled uncancelable block" in {
      IO.uncancelable(_ => IO.canceled).flatMap(_ => IO.pure(())) must nonTerminate
    }

    "cancel map continuations following a canceled uncancelable block" in {
      IO.uncancelable(_ => IO.canceled).map(_ => ()) must nonTerminate
    }

    "mapping something with a finalizer should complete" in {
      IO.pure(42).onCancel(IO.unit).as(()) must completeAs(())
    }

    "uncancelable canceled with finalizer within fiber should not block" in {
      val fab = IO.uncancelable(_ => IO.canceled.onCancel(IO.unit)).start.flatMap(_.join)

      fab must completeAs(Outcome.canceled[IO, Throwable, Unit])
    }

    "uncancelable canceled with finalizer within fiber should flatMap another day" in {
      val fa = IO.pure(42)
      val fab: IO[Int => Int] =
        IO.uncancelable(_ => IO.canceled.onCancel(IO.unit)).start.flatMap(_.join).flatMap(_ => IO.pure((i: Int) => i))

      fab.ap(fa) must completeAs(42)
      fab.flatMap(f => fa.map(f)) must completeAs(42)
    }

    "sleep for ten seconds" in {
      IO.sleep(10.seconds).as(1) must completeAs(1)
    }

    "sleep for ten seconds and continue" in {
      var affected = false
      (IO.sleep(10.seconds) >> IO { affected = true }) must completeAs(())
      affected must beTrue
    }

    "run an identity finalizer" in {
      var affected = false

      IO.unit onCase {
        case _ => IO { affected = true }
      } must completeAs(())

      affected must beTrue
    }

    "run an identity finalizer and continue" in {
      var affected = false

      val seed = IO.unit onCase {
        case _ => IO { affected = true }
      }

      seed.as(42) must completeAs(42)

      affected must beTrue
    }

    "run multiple nested finalizers on cancel" in {
      var inner = false
      var outer = false

      IO.canceled.guarantee(IO { inner = true }).guarantee(IO { outer = true }) must nonTerminate

      inner must beTrue
      outer must beTrue
    }

    "run multiple nested finalizers on completion exactly once" in {
      var inner = 0
      var outer = 0

      IO.unit.guarantee(IO { inner += 1 }).guarantee(IO { outer += 1 }) must completeAs(())

      inner mustEqual 1
      outer mustEqual 1
    }

    "sequence onCancel when canceled before registration" in {
      var passed = false
      val test = IO uncancelable { poll =>
        IO.canceled >> poll(IO.unit).onCancel(IO { passed = true })
      }

      test must nonTerminate
      passed must beTrue
    }

    "break out of uncancelable when canceled before poll" in {
      var passed = true
      val test = IO uncancelable { poll =>
        IO.canceled >> poll(IO.unit) >> IO { passed = false }
      }

      test must nonTerminate
      passed must beTrue
    }

    "invoke onCase finalizer when cancelable async returns" in {
      var passed = false

      // convenient proxy for an async that returns a cancelToken
      val test = IO.sleep(1.day) onCase {
        case Outcome.Completed(_) => IO { passed = true }
      }

      test must completeAs(())
      passed must beTrue
    }

    "hold onto errors through multiple finalizers" in {
      case object TestException extends RuntimeException
      IO.raiseError(TestException).guarantee(IO.unit).guarantee(IO.unit) must failAs(TestException)
    }

    "cede unit in a finalizer" in {
      val body = IO.sleep(1.second).start.flatMap(_.join).map(_ => 42)
      body.guarantee(IO.cede.map(_ => ())) must completeAs(42)
    }

    "not invoke onCancel when previously canceled within uncancelable" in {
      var failed = false
      IO.uncancelable(_ => IO.canceled >> IO.unit.onCancel(IO { failed = true })) must nonTerminate
      failed must beFalse
    }

    "complete a fiber with Canceled under finalizer on poll" in {
      val ioa = IO.uncancelable(p => IO.canceled >> p(IO.unit).guarantee(IO.unit)).start.flatMap(_.join)

      ioa must completeAs(Outcome.canceled[IO, Throwable, Unit])
    }

    "return the left when racing against never" in {
      IO.pure(42).racePair(IO.never: IO[Unit]).map(_.left.toOption.map(_._1)) must completeAs(Some(42))
    }

    "produce Canceled from start of canceled" in {
      IO.canceled.start.flatMap(_.join) must completeAs(Outcome.canceled[IO, Throwable, Unit])
    }

    "cancel an already canceled fiber" in {
      val test = for {
        f <- IO.canceled.start
        _ <- IO(ctx.tickAll())
        _ <- f.cancel
      } yield ()

      test must completeAs(())
    }

    "only unmask within current fiber" in {
      var passed = false
      val test = IO uncancelable { poll =>
        IO.uncancelable(_ => poll(IO.canceled >> IO { passed = true })).start.flatMap(_.join).void
      }

      test must completeAs(())
      passed must beTrue
    }

    "produce the left when the right errors in racePair" in {
      (IO.cede >> IO.pure(42)).racePair(IO.raiseError(new Throwable): IO[Unit]).map(_.left.toOption.map(_._1)) must completeAs(Some(42))
    }

    "run three finalizers when an async is canceled while suspended" in {
      var results = List[Int]()

      val body = IO.async[Nothing] { _ =>
        IO.pure(Some(IO(results ::= 3)))
      }

      val test = for {
        f <- body.onCancel(IO(results ::= 2)).onCancel(IO(results ::= 1)).start
        _ <- IO(ctx.tickAll())
        _ <- f.cancel
        back <- IO(results)
      } yield back

      test must completeAs(List(1, 2, 3))
    }

    "evaluate 10,000 consecutive map continuations" in {
      def loop(i: Int): IO[Unit] =
        if (i < 10000)
          IO.unit.flatMap(_ => loop(i + 1)).map(u => u)
        else
          IO.unit

      loop(0) must completeAs(())
    }

    "evaluate 10,000 consecutive handleErrorWith continuations" in {
      def loop(i: Int): IO[Unit] =
        if (i < 10000)
          IO.unit.flatMap(_ => loop(i + 1)).handleErrorWith(IO.raiseError(_))
        else
          IO.unit

      loop(0) must completeAs(())
    }

    "catch exceptions thrown in map functions" in {
      case object TestException extends RuntimeException
      IO.unit.map(_ => (throw TestException): Unit).attempt must completeAs(Left(TestException))
    }

    "catch exceptions thrown in flatMap functions" in {
      case object TestException extends RuntimeException
      IO.unit.flatMap(_ => (throw TestException): IO[Unit]).attempt must completeAs(Left(TestException))
    }

    "catch exceptions thrown in handleErrorWith functions" in {
      case object TestException extends RuntimeException
      case object WrongException extends RuntimeException
      IO.raiseError[Unit](WrongException).handleErrorWith(_ => (throw TestException): IO[Unit]).attempt must completeAs(Left(TestException))
    }

    "round trip through s.c.Future" in forAll { (ioa: IO[Int]) =>
      ioa eqv IO.fromFuture(IO(ioa.unsafeToFuture(unsafe.IORuntime(ctx, scheduler(), () => ()))))
    }

    "ignore repeated polls" in {
      var passed = true

      val test = IO uncancelable { poll =>
        poll(poll(IO.unit) >> IO.canceled) >> IO { passed = false }
      }

      test must nonTerminate
      passed must beTrue
    }

    "evaluate a timeout using sleep and race" in {
      IO.race(IO.never[Unit], IO.sleep(2.seconds)) must completeAs(Right(()))
    }

    platformSpecs
  }

  {
    checkAll(
      "IO",
      EffectTests[IO].effect[Int, Int, Int](10.millis))/*(Parameters(seed = Some(Seed.fromBase64("XidlR_tu11X7_v51XojzZJsm6EaeU99RAEL9vzbkWBD=").get)))*/

    checkAll(
      "IO[Int]",
      MonoidTests[IO[Int]].monoid)/*(Parameters(seed = Some(Seed.fromBase64("_1deH2u9O-z6PmkYMBgZT-3ofsMEAMStR9x0jKlFgyO=").get)))*/
  }

}

final case class TestException(i: Int) extends RuntimeException
