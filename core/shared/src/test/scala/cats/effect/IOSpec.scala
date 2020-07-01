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
import cats.effect.testkit.{AsyncGenerators, BracketGenerators, OutcomeGenerators, TestContext}
import cats.implicits._

import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}
import org.scalacheck.rng.Seed

import org.specs2.ScalaCheck
import org.specs2.scalacheck.Parameters
import org.specs2.mutable.Specification
import org.specs2.matcher.{Matcher, MatchersImplicits}, MatchersImplicits._

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

class IOSpec extends Specification with Discipline with ScalaCheck { outer =>
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
      infinite.start.flatMap(f => f.cancel >> f.join) must completeAs(Outcome.Canceled())
    }

    "cancel never" in {
      (IO.never: IO[Unit]).start.flatMap(f => f.cancel >> f.join) must completeAs(Outcome.Canceled())
    }

    "cancel never after scheduling" in {
      val ioa = for {
        f <- (IO.never: IO[Unit]).start
        ec <- IO.executionContext
        _ <- IO(ec.asInstanceOf[TestContext].tick())
        _ <- f.cancel
        oc <- f.join
      } yield oc

      ioa must completeAs(Outcome.Canceled())
    }

    "sequence async cancel token upon cancelation" in {
      var affected = false

      val target = IO.async[Unit] { _ =>
        IO.pure(Some(IO { affected = true }))
      }

      val ioa = for {
        f <- target.start
        ec <- IO.executionContext
        _ <- IO(ec.asInstanceOf[TestContext].tick())
        _ <- f.cancel
      } yield ()

      ioa must completeAs(())
      affected must beTrue
    }

    "preserve contexts through start" in {
      val ec = ctx.derive()

      val ioa = for {
        f <- IO.executionContext.start.evalOn(ec)
        _ <- IO(ctx.tick())
        oc <- f.join
      } yield oc

      ioa must completeAs(Outcome.Completed(IO.pure(ec)))
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

      fab must completeAs(Outcome.Canceled())
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

    "shift delay evaluation within evalOn" in {
      val Exec1Name = "testing executor 1"
      val exec1 = Executors newSingleThreadExecutor { r =>
        val t = new Thread(r)
        t.setName(Exec1Name)
        t
      }

      val Exec2Name = "testing executor 2"
      val exec2 = Executors newSingleThreadExecutor { r =>
        val t = new Thread(r)
        t.setName(Exec2Name)
        t
      }

      val Exec3Name = "testing executor 3"
      val exec3 = Executors newSingleThreadExecutor { r =>
        val t = new Thread(r)
        t.setName(Exec3Name)
        t
      }

      val nameF = IO(Thread.currentThread().getName())

      val test = nameF flatMap { outer1 =>
        val inner1F = nameF flatMap { inner1 =>
          val inner2F = nameF map { inner2 =>
            (outer1, inner1, inner2)
          }

          inner2F.evalOn(ExecutionContext.fromExecutor(exec2))
        }

        inner1F.evalOn(ExecutionContext.fromExecutor(exec1)) flatMap {
          case (outer1, inner1, inner2) =>
            nameF.map(outer2 => (outer1, inner1, inner2, outer2))
        }
      }

      implicit val t4s: Show[(String, String, String, String)] =
        Show.fromToString

      var result: Either[Throwable, (String, String, String, String)] = null
      val latch = new CountDownLatch(1)

      // this test is weird because we're making our own contexts, so we can't use TestContext at all
      test.unsafeRunAsync(ExecutionContext.fromExecutor(exec3), UnsafeTimer.fromScheduledExecutor(Executors.newScheduledThreadPool(1))) { e =>
        result = e
        latch.countDown()
      }

      latch.await()
      result must beRight((Exec3Name, Exec1Name, Exec2Name, Exec3Name))
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

      ioa must completeAs(Outcome.Canceled())
    }
  }

  {
    /*checkAll(
      "IO",
      EffectTests[IO].effect[Int, Int, Int](10.millis))*/

    checkAll(
      "IO",
      EffectTests[IO].bracket[Int, Int, Int])/*(Parameters(seed = Some(Seed.fromBase64("E_io0gq5LVd4Z_SwVLYxcuQ3MXdugm_juRiHFlLdIsL=").get)))*/

    checkAll(
      "IO[Int]",
      MonoidTests[IO[Int]].monoid)
  }

  // TODO organize the below somewhat better

  implicit def cogenIO[A: Cogen]: Cogen[IO[A]] =
    Cogen[Outcome[Option, Throwable, A]].contramap(unsafeRun(_))

  implicit def arbitraryIO[A: Arbitrary: Cogen]: Arbitrary[IO[A]] = {
    val generators =
      new AsyncGenerators[IO] with BracketGenerators[IO, Throwable] {

        val arbitraryE: Arbitrary[Throwable] =
          arbitraryThrowable

        val cogenE: Cogen[Throwable] = Cogen[Throwable]

        val F: AsyncBracket[IO] = IO.effectForIO

        def cogenCase[B: Cogen]: Cogen[Outcome[IO, Throwable, B]] =
          OutcomeGenerators.cogenOutcome[IO, Throwable, B]

        val arbitraryEC: Arbitrary[ExecutionContext] = outer.arbitraryEC

        val cogenFU: Cogen[IO[Unit]] = cogenIO[Unit]

        // TODO dedup with FreeSyncGenerators
        val arbitraryFD: Arbitrary[FiniteDuration] = {
          import TimeUnit._

          val genTU = Gen.oneOf(NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS)

          Arbitrary {
            genTU flatMap { u =>
              Gen.choose[Long](0L, 48L).map(FiniteDuration(_, u))
            }
          }
        }
      }

    Arbitrary(generators.generators[A])
  }

  implicit lazy val arbitraryThrowable: Arbitrary[Throwable] =
    Arbitrary(Arbitrary.arbitrary[Int].map(TestException))

  implicit lazy val arbitraryEC: Arbitrary[ExecutionContext] =
    Arbitrary(Gen.const(ctx.derive()))

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

  implicit def eqIOA[A: Eq]: Eq[IO[A]] = {
    /*Eq instance { (left: IO[A], right: IO[A]) =>
      val leftR = unsafeRun(left)
      val rightR = unsafeRun(right)

      val back = leftR eqv rightR

      if (!back && IO.trace) {
        println(s"$left != $right")
        println(s"$leftR != $rightR")
      }

      back
    }*/

    Eq.by(unsafeRun(_))
  }

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
    val timer = new UnsafeTimer {
      def sleep(delay: FiniteDuration, action: Runnable): Runnable = {
        val cancel = ctx.schedule(delay, action)
        new Runnable { def run() = cancel() }
      }
    }

    var results: Outcome[Option, Throwable, A] = Outcome.Completed(None)
    ioa.unsafeRunAsync(ctx, timer) {
      case Left(t) => results = Outcome.Errored(t)
      case Right(a) => results = Outcome.Completed(Some(a))
    }

    ctx.tick(3.days)    // longer than the maximum generator value of 48 hours

    // println("====================================")
    // println(s"completed ioa with $results")
    // println("====================================")

    results
  }
}

final case class TestException(i: Int) extends RuntimeException
