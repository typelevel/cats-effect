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

import cats.effect.kernel.Ref
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline.SemigroupKTests
import cats.effect.laws.AsyncTests
import cats.effect.testkit.{SyncTypeGenerators, TestContext}
import cats.syntax.all._

import org.scalacheck.Prop, Prop.forAll

import org.specs2.ScalaCheck

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.{ExecutionContext, TimeoutException}
import scala.concurrent.duration._

class IOSpec extends IOPlatformSpecification with Discipline with ScalaCheck with BaseSpec {
  outer =>

  import SyncTypeGenerators._

  // we just need this because of the laws testing, since the prop runs can interfere with each other
  sequential

  "io monad" should {

    "free monad" should {

      "produce a pure value when run" in ticked { implicit ticker =>
        IO.pure(42) must completeAs(42)
      }

      "map results to a new type" in ticked { implicit ticker =>
        IO.pure(42).map(_.toString) must completeAs("42")
      }

      "flatMap results sequencing both effects" in ticked { implicit ticker =>
        var i = 0
        IO.pure(42).flatMap(i2 => IO { i = i2 }) must completeAs(())
        i mustEqual 42
      }

      "preserve monad identity on async" in ticked { implicit ticker =>
        val fa = IO.async[Int](cb => IO(cb(Right(42))).as(None))
        fa.flatMap(i => IO.pure(i)) must completeAs(42)
        fa must completeAs(42)
      }

      "preserve monad right identity on uncancelable" in ticked { implicit ticker =>
        val fa = IO.uncancelable(_ => IO.canceled)
        fa.flatMap(IO.pure(_)) must nonTerminate
        fa must nonTerminate
      }

    }

    "error handling" should {
      "capture errors in suspensions" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO(throw TestException) must failAs(TestException)
      }

      "resume error continuation within async" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO.async[Unit](k => IO(k(Left(TestException))).as(None)) must failAs(TestException)
      }

      "raiseError propagates out" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO.raiseError(TestException).void.flatMap(_ => IO.pure(())) must failAs(TestException)
      }

      "errors can be handled" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO.raiseError[Unit](TestException).attempt must completeAs(Left(TestException))
      }

      "attempt is redeem with Left(_) for recover and Right(_) for map" in ticked {
        implicit ticker =>
          forAll { (io: IO[Int]) => io.attempt eqv io.redeem(Left(_), Right(_)) }
      }

      "attempt is flattened redeemWith" in ticked { implicit ticker =>
        forAll { (io: IO[Int], recover: Throwable => IO[String], bind: Int => IO[String]) =>
          io.attempt.flatMap(_.fold(recover, bind)) eqv io.redeemWith(recover, bind)
        }
      }

      "redeem is flattened redeemWith" in ticked { implicit ticker =>
        forAll { (io: IO[Int], recover: Throwable => IO[String], bind: Int => IO[String]) =>
          io.redeem(recover, bind).flatten eqv io.redeemWith(recover, bind)
        }
      }

      "redeem subsumes handleError" in ticked { implicit ticker =>
        forAll { (io: IO[Int], recover: Throwable => Int) =>
          io.redeem(recover, identity) eqv io.handleError(recover)
        }
      }

      "redeemWith subsumes handleErrorWith" in ticked { implicit ticker =>
        forAll { (io: IO[Int], recover: Throwable => IO[Int]) =>
          io.redeemWith(recover, IO.pure) eqv io.handleErrorWith(recover)
        }
      }

      "redeem correctly recovers from errors" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO.raiseError[Unit](TestException).redeem(_ => 42, _ => 43) must completeAs(42)
      }

      "redeem maps successful results" in ticked { implicit ticker =>
        IO.unit.redeem(_ => 41, _ => 42) must completeAs(42)
      }

      "redeem catches exceptions thrown in recovery function" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        case object ThrownException extends RuntimeException
        IO.raiseError[Unit](TestException)
          .redeem(_ => throw ThrownException, _ => 42)
          .attempt must completeAs(Left(ThrownException))
      }

      "redeem catches exceptions thrown in map function" in ticked { implicit ticker =>
        case object ThrownException extends RuntimeException
        IO.unit.redeem(_ => 41, _ => throw ThrownException).attempt must completeAs(
          Left(ThrownException))
      }

      "redeemWith correctly recovers from errors" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO.raiseError[Unit](TestException)
          .redeemWith(_ => IO.pure(42), _ => IO.pure(43)) must completeAs(42)
      }

      "redeemWith binds successful results" in ticked { implicit ticker =>
        IO.unit.redeemWith(_ => IO.pure(41), _ => IO.pure(42)) must completeAs(42)
      }

      "redeemWith catches exceptions throw in recovery function" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        case object ThrownException extends RuntimeException
        IO.raiseError[Unit](TestException)
          .redeemWith(_ => throw ThrownException, _ => IO.pure(42))
          .attempt must completeAs(Left(ThrownException))
      }

      "redeemWith catches exceptions thrown in bind function" in ticked { implicit ticker =>
        case object ThrownException extends RuntimeException
        IO.unit.redeem(_ => IO.pure(41), _ => throw ThrownException).attempt must completeAs(
          Left(ThrownException))
      }

      "catch exceptions thrown in map functions" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO.unit.map(_ => (throw TestException): Unit).attempt must completeAs(
          Left(TestException))
      }

      "catch exceptions thrown in flatMap functions" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO.unit.flatMap(_ => (throw TestException): IO[Unit]).attempt must completeAs(
          Left(TestException))
      }

      "catch exceptions thrown in handleErrorWith functions" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        case object WrongException extends RuntimeException
        IO.raiseError[Unit](WrongException)
          .handleErrorWith(_ => (throw TestException): IO[Unit])
          .attempt must completeAs(Left(TestException))
      }

    }

    "suspension of side effects" should {

      "suspend a side-effect without memoizing" in ticked { implicit ticker =>
        var i = 42

        val ioa = IO {
          i += 1
          i
        }

        ioa must completeAs(43)
        ioa must completeAs(44)
      }

      "result in an null if lifting a pure null value" in ticked { implicit ticker =>
        // convoluted in order to avoid scalac warnings
        IO.pure(null).map(_.asInstanceOf[Any]).map(_ == null) must completeAs(true)
      }

      "result in an NPE if delaying a null value" in ticked { implicit ticker =>
        IO(null).map(_.asInstanceOf[Any]).map(_ == null) must completeAs(true)
        IO.delay(null).map(_.asInstanceOf[Any]).map(_ == null) must completeAs(true)
      }

      "result in an NPE if deferring a null IO" in ticked { implicit ticker =>
        IO.defer(null)
          .attempt
          .map(_.left.toOption.get.isInstanceOf[NullPointerException]) must completeAs(true)
      }

    }

    "fibers" should {

      "start and join on a successful fiber" in ticked { implicit ticker =>
        IO.pure(42).map(_ + 1).start.flatMap(_.join) must completeAs(
          Outcome.completed[IO, Throwable, Int](IO.pure(43)))
      }

      "start and join on a failed fiber" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO.raiseError[Unit](TestException).start.flatMap(_.join) must completeAs(
          Outcome.errored[IO, Throwable, Unit](TestException))
      }

      "start and ignore a non-terminating fiber" in ticked { implicit ticker =>
        IO.never.start.as(42) must completeAs(42)
      }

      "start a fiber then continue with its results" in ticked { implicit ticker =>
        IO.pure(42).start.flatMap(_.join).flatMap { oc =>
          oc.fold(IO.pure(0), _ => IO.pure(-1), ioa => ioa)
        } must completeAs(42)
      }

      "joinAndEmbedNever on a cancelled fiber" in ticked { implicit ticker =>
        (for {
          fib <- IO.sleep(2.seconds).start
          _ <- fib.cancel
          _ <- fib.joinAndEmbedNever
        } yield ()) must nonTerminate
      }

      "joinAndEmbedNever on a successful fiber" in ticked { implicit ticker =>
        (for {
          fib <- IO.pure(1).start
          res <- fib.joinAndEmbedNever
        } yield res) must completeAs(1)
      }

      "joinAndEmbedNever on a failed fiber" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        (for {
          fib <- IO.raiseError[Unit](TestException).start
          res <- fib.joinAndEmbedNever
        } yield res) must failAs(TestException)
      }

      "preserve contexts through start" in ticked { implicit ticker =>
        val ec = ticker.ctx.derive()

        val ioa = for {
          f <- IO.executionContext.start.evalOn(ec)
          _ <- IO(ticker.ctx.tickAll())
          oc <- f.join
        } yield oc

        ioa must completeAs(Outcome.completed[IO, Throwable, ExecutionContext](IO.pure(ec)))
      }

      "produce Canceled from start of canceled" in ticked { implicit ticker =>
        IO.canceled.start.flatMap(_.join) must completeAs(Outcome.canceled[IO, Throwable, Unit])
      }

      "cancel an already canceled fiber" in ticked { implicit ticker =>
        val test = for {
          f <- IO.canceled.start
          _ <- IO(ticker.ctx.tickAll())
          _ <- f.cancel
        } yield ()

        test must completeAs(())
      }

    }

    "async" should {

      "resume value continuation within async" in ticked { implicit ticker =>
        IO.async[Int](k => IO(k(Right(42))).map(_ => None)) must completeAs(42)
      }

      "continue from the results of an async produced prior to registration" in ticked {
        implicit ticker =>
          IO.async[Int](cb => IO(cb(Right(42))).as(None)).map(_ + 2) must completeAs(44)
      }

      // format: off
      "produce a failure when the registration raises an error after callback" in ticked { implicit ticker =>
        case object TestException extends RuntimeException

        IO.async[Int](cb => IO(cb(Right(42)))
          .flatMap(_ => IO.raiseError(TestException)))
          .void must failAs(TestException)
      }
    // format: on

      "repeated async callback" in ticked { implicit ticker =>
        case object TestException extends RuntimeException

        var cb: Either[Throwable, Int] => Unit = null

        val async = IO.async_[Int] { cb0 => cb = cb0 }

        val test = for {
          fiber <- async.start
          _ <- IO(ticker.ctx.tickAll())
          _ <- IO(cb(Right(42)))
          _ <- IO(ticker.ctx.tickAll())
          _ <- IO(cb(Right(43)))
          _ <- IO(ticker.ctx.tickAll())
          _ <- IO(cb(Left(TestException)))
          _ <- IO(ticker.ctx.tickAll())
          value <- fiber.joinAndEmbedNever
        } yield value

        test must completeAs(42)
      }

      "complete a fiber with Canceled under finalizer on poll" in ticked { implicit ticker =>
        val ioa =
          IO.uncancelable(p => IO.canceled >> p(IO.unit).guarantee(IO.unit))
            .start
            .flatMap(_.join)

        ioa must completeAs(Outcome.canceled[IO, Throwable, Unit])
      }

      "invoke multiple joins on fiber completion" in real {
        val test = for {
          f <- IO.pure(42).start

          delegate1 <- f.join.start
          delegate2 <- f.join.start
          delegate3 <- f.join.start
          delegate4 <- f.join.start

          _ <- IO.cede

          r1 <- delegate1.join
          r2 <- delegate2.join
          r3 <- delegate3.join
          r4 <- delegate4.join
        } yield List(r1, r2, r3, r4)

        test.flatMap { results =>
          results.traverse { result =>
            IO(result must beLike { case Outcome.Succeeded(_) => ok }).flatMap { _ =>
              result match {
                case Outcome.Succeeded(ioa) =>
                  ioa.flatMap { oc =>
                    IO(result must beLike { case Outcome.Succeeded(_) => ok }).flatMap { _ =>
                      oc match {
                        case Outcome.Succeeded(ioa) =>
                          ioa flatMap { i => IO(i mustEqual 42) }

                        case _ => sys.error("nope")
                      }
                    }
                  }

                case _ => sys.error("nope")
              }
            }
          }
        }
      }

      "both" should {

        "succeed if both sides succeed" in ticked { implicit ticker =>
          IO.both(IO.pure(1), IO.pure(2)) must completeAs((1, 2))
        }

        "fail if lhs fails" in ticked { implicit ticker =>
          case object TestException extends Throwable
          IO.both(IO.raiseError(TestException), IO.pure(2)).void must failAs(TestException)
        }

        "fail if rhs fails" in ticked { implicit ticker =>
          case object TestException extends Throwable
          IO.both(IO.pure(2), IO.raiseError(TestException)).void must failAs(TestException)
        }

        "cancel if lhs cancels" in ticked { implicit ticker =>
          IO.both(IO.canceled, IO.unit).void.start.flatMap(_.join) must completeAs(Outcome.canceled[IO, Throwable, Unit])

        }

        "cancel if rhs cancels" in ticked { implicit ticker =>
          IO.both(IO.unit, IO.canceled).void.start.flatMap(_.join) must completeAs(Outcome.canceled[IO, Throwable, Unit])
        }

        "non terminate if lhs never completes" in ticked { implicit ticker =>
          IO.both(IO.never, IO.pure(1)).void must nonTerminate
        }

        "non terminate if rhs never completes" in ticked { implicit ticker =>
          IO.both(IO.pure(1), IO.never).void must nonTerminate
        }

        "propagate cancellation" in ticked { implicit ticker =>
          (for {
            fiber <- IO.both(IO.never, IO.never).void.start
            _ <- IO(ticker.ctx.tickAll())
            _ <- fiber.cancel
            _ <- IO(ticker.ctx.tickAll())
            oc <- fiber.join
          } yield oc) must completeAs(Outcome.canceled[IO, Throwable, Unit])
        }

        "cancel both fibers" in ticked { implicit ticker =>
          (for {
            l <- Ref[IO].of(false)
            r <- Ref[IO].of(false)
            fiber <-
              IO.both(
                IO.never.onCancel(l.set(true)),
                IO.never.onCancel(r.set(true)))
                .start
            _ <- IO(ticker.ctx.tickAll())
            _ <- fiber.cancel
            l2 <- l.get
            r2 <- r.get
          } yield (l2 -> r2)) must completeAs(true -> true)
        }

      }

      "race" should {
        "succeed with faster side" in ticked { implicit ticker =>
          IO.race(IO.sleep(10 minutes) >> IO.pure(1), IO.pure(2)) must completeAs(Right(2))
        }

        "fail if lhs fails" in ticked { implicit ticker =>
          case object TestException extends Throwable
          IO.race(IO.raiseError[Int](TestException), IO.sleep(10 millis) >> IO.pure(1)).void must failAs(TestException)
        }

        "fail if rhs fails" in ticked { implicit ticker =>
          case object TestException extends Throwable
          IO.race(IO.sleep(10 millis) >> IO.pure(1), IO.raiseError[Int](TestException)).void must failAs(TestException)
        }

        "fail if lhs fails and rhs never completes" in ticked { implicit ticker =>
          case object TestException extends Throwable
          IO.race(IO.raiseError[Int](TestException), IO.never).void must failAs(TestException)
        }

        "fail if rhs fails and lhs never completes" in ticked { implicit ticker =>
          case object TestException extends Throwable
          IO.race(IO.never, IO.raiseError[Int](TestException)).void must failAs(TestException)
        }

        "succeed if lhs never completes" in ticked { implicit ticker =>
          IO.race(IO.never[Int], IO.pure(2)) must completeAs(Right(2))
        }

        "succeed if rhs never completes" in ticked { implicit ticker =>
          IO.race(IO.pure(2), IO.never[Int]) must completeAs(Left(2))
        }

        "cancel if both sides cancel" in ticked { implicit ticker =>
          IO.both(IO.canceled, IO.canceled).void.start.flatMap(_.join) must completeAs(Outcome.canceled[IO, Throwable, Unit])
        }

        "succeed if lhs cancels" in ticked { implicit ticker =>
          IO.race(IO.canceled, IO.pure(1)) must completeAs(Right(1))
        }

        "succeed if rhs cancels" in ticked { implicit ticker =>
          IO.race(IO.pure(1), IO.canceled) must completeAs(Left(1))
        }

        "fail if lhs cancels and rhs fails" in ticked { implicit ticker =>
          case object TestException extends Throwable
          IO.race(IO.canceled, IO.raiseError[Unit](TestException)).void must failAs(TestException)
        }

        "fail if rhs cancels and lhs fails" in ticked { implicit ticker =>
          case object TestException extends Throwable
          IO.race(IO.raiseError[Unit](TestException), IO.canceled).void must failAs(TestException)
        }

        "cancel both fibers" in ticked { implicit ticker =>
          (for {
            l <- Ref.of[IO, Boolean](false)
            r <- Ref.of[IO, Boolean](false)
            fiber <-
              IO.race(
                IO.never.onCancel(l.set(true)),
                IO.never.onCancel(r.set(true)))
                .start
            _ <- IO(ticker.ctx.tickAll())
            _ <- fiber.cancel
            l2 <- l.get
            r2 <- r.get
          } yield (l2 -> r2)) must completeAs(true -> true)
        }
  
        "evaluate a timeout using sleep and race" in ticked { implicit ticker =>
          IO.race(IO.never[Unit], IO.sleep(2.seconds)) must completeAs(Right(()))
        }

        "evaluate a timeout using sleep and race in real time" in real {
          IO.race(IO.never[Unit], IO.sleep(10.millis)).flatMap { res =>
            IO {
              res must beRight(())
            }
          }
        }

        "return the left when racing against never" in ticked { implicit ticker =>
          IO.pure(42)
            .racePair(IO.never: IO[Unit])
            .map(_.left.toOption.map(_._1).get) must completeAs(
            Outcome.completed[IO, Throwable, Int](IO.pure(42)))
        }

      }

    }

    "cancellation" should {

      "implement never with non-terminating semantics" in ticked { implicit ticker =>
        IO.never must nonTerminate
      }

      "cancel an infinite chain of right-binds" in ticked { implicit ticker =>
        lazy val infinite: IO[Unit] = IO.unit.flatMap(_ => infinite)
        infinite.start.flatMap(f => f.cancel >> f.join) must completeAs(
          Outcome.canceled[IO, Throwable, Unit])
      }

      "cancel never" in ticked { implicit ticker =>
        (IO.never: IO[Unit]).start.flatMap(f => f.cancel >> f.join) must completeAs(
          Outcome.canceled[IO, Throwable, Unit])
      }

      "cancel never after scheduling" in ticked { implicit ticker =>
        val ioa = for {
          f <- (IO.never: IO[Unit]).start
          ec <- IO.executionContext
          _ <- IO(ec.asInstanceOf[TestContext].tickAll())
          _ <- f.cancel
          oc <- f.join
        } yield oc

        ioa must completeAs(Outcome.canceled[IO, Throwable, Unit])
      }

      "sequence async cancel token upon cancelation during suspension" in ticked {
        implicit ticker =>
          var affected = false

          val target = IO.async[Unit] { _ => IO.pure(Some(IO { affected = true })) }

          val ioa = for {
            f <- target.start
            _ <- IO(ticker.ctx.tickAll())
            _ <- f.cancel
          } yield ()

          ioa must completeAs(())
          affected must beTrue
      }

      "suppress async cancel token upon cancelation in masked region" in ticked {
        implicit ticker =>
          var affected = false

          val target = IO uncancelable { _ =>
            IO.async[Unit] { _ => IO.pure(Some(IO { affected = true })) }
          }

          val ioa = for {
            f <- target.start
            _ <- IO(ticker.ctx.tickAll())
            _ <- f.cancel
          } yield ()

          ioa must nonTerminate // we're canceling an uncancelable never
          affected must beFalse
      }

      "cancel flatMap continuations following a canceled uncancelable block" in ticked {
        implicit ticker =>
          IO.uncancelable(_ => IO.canceled).flatMap(_ => IO.pure(())) must nonTerminate
      }

      "cancel map continuations following a canceled uncancelable block" in ticked {
        implicit ticker => IO.uncancelable(_ => IO.canceled).map(_ => ()) must nonTerminate
      }

      "sequence onCancel when canceled before registration" in ticked { implicit ticker =>
        var passed = false
        val test = IO.uncancelable { poll =>
          IO.canceled >> poll(IO.unit).onCancel(IO { passed = true })
        }

        test must nonTerminate
        passed must beTrue
      }

      "break out of uncancelable when canceled before poll" in ticked { implicit ticker =>
        var passed = true
        val test = IO.uncancelable { poll =>
          IO.canceled >> poll(IO.unit) >> IO { passed = false }
        }

        test must nonTerminate
        passed must beTrue
      }

      "not invoke onCancel when previously canceled within uncancelable" in ticked {
        implicit ticker =>
          var failed = false
          IO.uncancelable(_ =>
            IO.canceled >> IO.unit.onCancel(IO { failed = true })) must nonTerminate
          failed must beFalse
      }

      "only unmask within current fiber" in ticked { implicit ticker =>
        var passed = false
        val test = IO uncancelable { poll =>
          IO.uncancelable(_ => poll(IO.canceled >> IO { passed = true }))
            .start
            .flatMap(_.join)
            .void
        }

        test must completeAs(())
        passed must beTrue
      }

      "run three finalizers when an async is canceled while suspended" in ticked {
        implicit ticker =>
          var results = List[Int]()

          val body = IO.async[Nothing] { _ => IO.pure(Some(IO(results ::= 3))) }

          val test = for {
            f <- body.onCancel(IO(results ::= 2)).onCancel(IO(results ::= 1)).start
            _ <- IO(ticker.ctx.tickAll())
            _ <- f.cancel
            back <- IO(results)
          } yield back

          test must completeAs(List(1, 2, 3))
      }

      "uncancelable canceled with finalizer within fiber should not block" in ticked {
        implicit ticker =>
          val fab = IO.uncancelable(_ => IO.canceled.onCancel(IO.unit)).start.flatMap(_.join)

          fab must completeAs(Outcome.canceled[IO, Throwable, Unit])
      }

      "uncancelable canceled with finalizer within fiber should flatMap another day" in ticked {
        implicit ticker =>
          val fa = IO.pure(42)
          val fab: IO[Int => Int] =
            IO.uncancelable(_ => IO.canceled.onCancel(IO.unit))
              .start
              .flatMap(_.join)
              .flatMap(_ => IO.pure((i: Int) => i))

          fab.ap(fa) must completeAs(42)
          fab.flatMap(f => fa.map(f)) must completeAs(42)
      }

      "ignore repeated polls" in ticked { implicit ticker =>
        var passed = true

        val test = IO.uncancelable { poll =>
          poll(poll(IO.unit) >> IO.canceled) >> IO { passed = false }
        }

        test must nonTerminate
        passed must beTrue
      }

      "never terminate when racing infinite cancels" in ticked { implicit ticker =>
        var started = false

        val markStarted = IO { started = true }
        lazy val cedeUntilStarted: IO[Unit] =
          IO(started).ifM(IO.unit, IO.cede >> cedeUntilStarted)

        val test = for {
          f <- (markStarted *> IO.never).onCancel(IO.never).start
          _ <- cedeUntilStarted
          _ <- IO.race(f.cancel, f.cancel)
        } yield ()

        test should nonTerminate
      }

      "first canceller backpressures subsequent cancellers" in ticked { implicit ticker =>
        var started = false

        val markStarted = IO { started = true }
        lazy val cedeUntilStarted: IO[Unit] =
          IO(started).ifM(IO.unit, IO.cede >> cedeUntilStarted)

        var started2 = false

        val markStarted2 = IO { started2 = true }
        lazy val cedeUntilStarted2: IO[Unit] =
          IO(started2).ifM(IO.unit, IO.cede >> cedeUntilStarted2)

        val test = for {
          first <- (markStarted *> IO.never).onCancel(IO.never).start
          _ <- (cedeUntilStarted *> markStarted2 *> first.cancel).start
          _ <- cedeUntilStarted2 *> first.cancel
        } yield ()

        test must nonTerminate
      }

      "reliably cancel infinite IO.cede(s)" in real {
        IO.cede.foreverM.start.flatMap(f => IO.sleep(50.millis) >> f.cancel).as(ok)
      }

      "await uncancelable blocks in cancelation" in ticked { implicit ticker =>
        var started = false

        val markStarted = IO { started = true }
        lazy val cedeUntilStarted: IO[Unit] =
          IO(started).ifM(IO.unit, IO.cede >> cedeUntilStarted)

        IO.uncancelable(_ => markStarted *> IO.never)
          .start
          .flatMap(f => cedeUntilStarted *> f.cancel) must nonTerminate
      }

      "await cancelation of cancelation of uncancelable never" in ticked { implicit ticker =>
        var started = false

        val markStarted = IO { started = true }
        lazy val cedeUntilStarted: IO[Unit] =
          IO(started).ifM(IO.unit, IO.cede >> cedeUntilStarted)

        var started2 = false

        val markStarted2 = IO { started2 = true }
        lazy val cedeUntilStarted2: IO[Unit] =
          IO(started2).ifM(IO.unit, IO.cede >> cedeUntilStarted2)

        val test = for {
          first <- IO.uncancelable(_ => markStarted *> IO.never).start
          second <-
            IO.uncancelable(p => cedeUntilStarted *> markStarted2 *> p(first.cancel)).start
          _ <- cedeUntilStarted2
          _ <- second.cancel
        } yield ()

        test must nonTerminate
      }

    }

    "finalization" should {

      "mapping something with a finalizer should complete" in ticked { implicit ticker =>
        IO.pure(42).onCancel(IO.unit).as(()) must completeAs(())
      }

      "run an identity finalizer" in ticked { implicit ticker =>
        var affected = false

        IO.unit.onCase {
          case _ => IO { affected = true }
        } must completeAs(())

        affected must beTrue
      }

      "run an identity finalizer and continue" in ticked { implicit ticker =>
        var affected = false

        val seed = IO.unit.onCase {
          case _ => IO { affected = true }
        }

        seed.as(42) must completeAs(42)

        affected must beTrue
      }

      "run multiple nested finalizers on cancel" in ticked { implicit ticker =>
        var inner = false
        var outer = false

        IO.canceled
          .guarantee(IO { inner = true })
          .guarantee(IO { outer = true }) must nonTerminate

        inner must beTrue
        outer must beTrue
      }

      "run multiple nested finalizers on completion exactly once" in ticked { implicit ticker =>
        var inner = 0
        var outer = 0

        IO.unit.guarantee(IO(inner += 1)).guarantee(IO(outer += 1)) must completeAs(())

        inner mustEqual 1
        outer mustEqual 1
      }

      "invoke onCase finalizer when cancelable async returns" in ticked { implicit ticker =>
        var passed = false

        // convenient proxy for an async that returns a cancelToken
        val test = IO.sleep(1.day).onCase {
          case Outcome.Succeeded(_) => IO { passed = true }
        }

        test must completeAs(())
        passed must beTrue
      }

      "hold onto errors through multiple finalizers" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO.raiseError(TestException).guarantee(IO.unit).guarantee(IO.unit) must failAs(
          TestException)
      }

      "cede unit in a finalizer" in ticked { implicit ticker =>
        val body = IO.sleep(1.second).start.flatMap(_.join).map(_ => 42)
        body.guarantee(IO.cede.map(_ => ())) must completeAs(42)
      }

      "ensure async callback is suppressed during suspension of async finalizers" in ticked {
        implicit ticker =>
          var cb: Either[Throwable, Unit] => Unit = null

          val subject = IO.async[Unit] { cb0 =>
            IO {
              cb = cb0

              Some(IO.never)
            }
          }

          val test = for {
            f <- subject.start
            _ <- IO(ticker.ctx.tickAll()) // schedule everything
            _ <- f.cancel.start
            _ <- IO(ticker.ctx.tickAll()) // get inside the finalizer suspension
            _ <- IO(cb(Right(())))
            _ <- IO(ticker.ctx.tickAll()) // show that the finalizer didn't explode
          } yield ()

          test must completeAs(()) // ...but not throw an exception
      }

      "run the continuation of an async finalizer within async" in ticked { implicit ticker =>
        var success = false

        val target = IO.async[Unit] { _ =>
          val fin = IO.async_[Unit] { cb => ticker.ctx.execute(() => cb(Right(()))) } *> IO {
            success = true
          }

          IO.pure(Some(fin))
        }

        val test = target.start flatMap { f => IO(ticker.ctx.tickAll()) *> f.cancel }

        test must completeAs(())
        success must beTrue
      }

    }

    "stack-safety" should {

      "evaluate 10,000 consecutive map continuations" in ticked { implicit ticker =>
        def loop(i: Int): IO[Unit] =
          if (i < 10000)
            IO.unit.flatMap(_ => loop(i + 1)).map(u => u)
          else
            IO.unit

        loop(0) must completeAs(())
      }

      "evaluate 10,000 consecutive handleErrorWith continuations" in ticked { implicit ticker =>
        def loop(i: Int): IO[Unit] =
          if (i < 10000)
            IO.unit.flatMap(_ => loop(i + 1)).handleErrorWith(IO.raiseError(_))
          else
            IO.unit

        loop(0) must completeAs(())
      }

    }

    "miscellaneous" should {

      "round trip through s.c.Future" in ticked { implicit ticker =>
        forAll { (ioa: IO[Int]) => ioa eqv IO.fromFuture(IO(ioa.unsafeToFuture())) }
      }

      "run parallel actually in parallel" in real {
        val x = IO.sleep(2.seconds) >> IO.pure(1)
        val y = IO.sleep(2.seconds) >> IO.pure(2)

        List(x, y).parSequence.timeout(3.seconds).flatMap { res =>
          IO {
            res mustEqual List(1, 2)
          }
        }
      }

    }

    "temporal" should {

      "sleep for ten seconds" in ticked { implicit ticker =>
        IO.sleep(10.seconds).as(1) must completeAs(1)
      }

      "sleep for ten seconds and continue" in ticked { implicit ticker =>
        var affected = false
        (IO.sleep(10.seconds) >> IO { affected = true }) must completeAs(())
        affected must beTrue
      }
      "timeout" should {
        "succeed" in real {
          val op = IO.pure(true).timeout(100.millis)

          op.flatMap { res =>
            IO {
              res must beTrue
            }
          }
        }

        "cancel a loop" in real {
          val loop = IO.cede.foreverM

          val op = loop.timeout(5.millis).attempt

          op.flatMap { res =>
            IO {
              res must beLike {
                case Left(e) => e must haveClass[TimeoutException]
              }
            }
          }
        }
      }

      "timeoutTo" should {
        "succeed" in real {
          val op =
            IO.pure(true).timeoutTo(5.millis, IO.raiseError(new RuntimeException))

          op.flatMap { res =>
            IO {
              res must beTrue
            }
          }
        }

        "use fallback" in real {
          val loop = IO.cede.foreverM

          val op = loop.timeoutTo(5.millis, IO.pure(true))

          op.flatMap { res =>
            IO {
              res must beTrue
            }
          }
        }
      }
    }

    platformSpecs
  }

  {
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "IO",
      AsyncTests[IO].async[Int, Int, Int](10.millis)
    ) /*(Parameters(seed = Some(Seed.fromBase64("XidlR_tu11X7_v51XojzZJsm6EaeU99RAEL9vzbkWBD=").get)))*/
  }

  {
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "IO[Int]",
      MonoidTests[IO[Int]].monoid
    ) /*(Parameters(seed = Some(Seed.fromBase64("_1deH2u9O-z6PmkYMBgZT-3ofsMEAMStR9x0jKlFgyO=").get)))*/
  }

  {
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "IO[Int]",
      SemigroupKTests[IO].semigroupK[Int]
    )
  }

}

final case class TestException(i: Int) extends RuntimeException
