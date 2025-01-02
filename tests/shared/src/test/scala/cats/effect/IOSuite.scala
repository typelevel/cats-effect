/*
 * Copyright 2020-2025 Typelevel
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
import cats.effect.laws.AsyncTests
import cats.effect.testkit.{TestContext, TestControl}
import cats.kernel.laws.SerializableLaws.serializable
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline.{AlignTests, SemigroupKTests}
import cats.laws.discipline.arbitrary._
import cats.syntax.all._
import cats.~>

import org.scalacheck.Prop

import scala.concurrent.{CancellationException, ExecutionContext, Promise, TimeoutException}
import scala.concurrent.duration._

import munit.{DisciplineSuite, ScalaCheckSuite}

import Prop.forAll

class IOSuite extends BaseSuite with DisciplineSuite with ScalaCheckSuite with IOPlatformSuite {

  ticked("free monad - produce a pure value when run") { implicit ticker =>
    assertCompleteAs(IO.pure(42), 42)
  }

  ticked("free monad - map results to a new type") { implicit ticker =>
    assertCompleteAs(IO.pure(42).map(_.toString), "42")
  }

  ticked("free monad - flatMap results sequencing both effects") { implicit ticker =>
    var i = 0
    assertCompleteAs(IO.pure(42).flatMap(i2 => IO { i = i2 }), ())
    assertEquals(i, 42)
  }

  ticked("free monad - preserve monad identity on asyncCheckAttempt immediate result") {
    implicit ticker =>
      val fa = IO.asyncCheckAttempt[Int](_ => IO(Right(42)))
      assertCompleteAs(fa.flatMap(i => IO.pure(i)), 42)
      assertCompleteAs(fa, 42)
  }

  ticked("free monad - preserve monad identity on asyncCheckAttempt suspended result") {
    implicit ticker =>
      val fa = IO.asyncCheckAttempt[Int](cb => IO(cb(Right(42))).as(Left(None)))
      assertCompleteAs(fa.flatMap(i => IO.pure(i)), 42)
      assertCompleteAs(fa, 42)
  }

  ticked("free monad - preserve monad identity on async") { implicit ticker =>
    val fa = IO.async[Int](cb => IO(cb(Right(42))).as(None))
    assertCompleteAs(fa.flatMap(i => IO.pure(i)), 42)
    assertCompleteAs(fa, 42)
  }

  ticked("error handling - capture errors in suspensions") { implicit ticker =>
    case object TestException extends RuntimeException
    assertFailAs(IO(throw TestException), TestException)
  }

  ticked("error handling - resume error continuation within asyncCheckAttempt") {
    implicit ticker =>
      case object TestException extends RuntimeException
      assertFailAs(
        IO.asyncCheckAttempt[Unit](k => IO(k(Left(TestException))).as(Left(None))),
        TestException)
  }

  ticked("error handling - resume error continuation within async") { implicit ticker =>
    case object TestException extends RuntimeException
    assertFailAs(IO.async[Unit](k => IO(k(Left(TestException))).as(None)), TestException)
  }

  ticked("error handling - raiseError propagates out") { implicit ticker =>
    case object TestException extends RuntimeException
    assertFailAs(IO.raiseError(TestException).void.flatMap(_ => IO.pure(())), TestException)
  }

  ticked("error handling - errors can be handled") { implicit ticker =>
    case object TestException extends RuntimeException
    assertCompleteAs(IO.raiseError[Unit](TestException).attempt, Left(TestException))
  }

  ticked("error handling - orElse must return other if previous IO Fails") { implicit ticker =>
    case object TestException extends RuntimeException
    assertCompleteAs(IO.raiseError[Int](TestException) orElse IO.pure(42), 42)
  }

  ticked("error handling - Return current IO if successful") { implicit ticker =>
    case object TestException extends RuntimeException
    assertCompleteAs(IO.pure(42) orElse IO.raiseError[Int](TestException), 42)
  }

  ticked("error handling - adaptError is a no-op for a successful effect") { implicit ticker =>
    assertCompleteAs(IO(42).adaptError { case x => x }, 42)
  }

  ticked("error handling - adaptError is a no-op for a non-matching error") { implicit ticker =>
    case object TestException1 extends RuntimeException
    case object TestException2 extends RuntimeException
    assertFailAs(
      IO.raiseError[Unit](TestException1).adaptError { case TestException2 => TestException2 },
      TestException1)
  }

  ticked("error handling - adaptError transforms the error in a failed effect") {
    implicit ticker =>
      case object TestException1 extends RuntimeException
      case object TestException2 extends RuntimeException
      assertFailAs(
        IO.raiseError[Unit](TestException1).adaptError {
          case TestException1 => TestException2
        },
        TestException2)
  }

  ticked("error handling - attempt is redeem with Left(_) for recover and Right(_) for map") {
    implicit ticker => forAll { (io: IO[Int]) => io.attempt eqv io.redeem(Left(_), Right(_)) }
  }

  ticked("error handling - attempt is flattened redeemWith") { implicit ticker =>
    forAll { (io: IO[Int], recover: Throwable => IO[String], bind: Int => IO[String]) =>
      io.attempt.flatMap(_.fold(recover, bind)) eqv io.redeemWith(recover, bind)
    }
  }

  ticked("error handling - attemptTap(f) is an alias for attempt.flatTap(f).rethrow") {
    implicit ticker =>
      forAll { (io: IO[Int], f: Either[Throwable, Int] => IO[Int]) =>
        io.attemptTap(f) eqv io.attempt.flatTap(f).rethrow
      }
  }

  ticked("error handling - rethrow is inverse of attempt") { implicit ticker =>
    forAll { (io: IO[Int]) => io.attempt.rethrow eqv io }
  }

  ticked("error handling - redeem is flattened redeemWith") { implicit ticker =>
    forAll { (io: IO[Int], recover: Throwable => IO[String], bind: Int => IO[String]) =>
      io.redeem(recover, bind).flatten eqv io.redeemWith(recover, bind)
    }
  }

  ticked("error handling - redeem subsumes handleError") { implicit ticker =>
    forAll { (io: IO[Int], recover: Throwable => Int) =>
      // we have to workaround functor law weirdness here... again... sigh... because of self-cancelation
      io.redeem(recover, identity).flatMap(IO.pure(_)) eqv io.handleError(recover)
    }
  }

  ticked("error handling - redeemWith subsumes handleErrorWith") { implicit ticker =>
    forAll { (io: IO[Int], recover: Throwable => IO[Int]) =>
      io.redeemWith(recover, IO.pure) eqv io.handleErrorWith(recover)
    }
  }

  ticked("error handling - redeem correctly recovers from errors") { implicit ticker =>
    case object TestException extends RuntimeException
    assertCompleteAs(IO.raiseError[Unit](TestException).redeem(_ => 42, _ => 43), 42)
  }

  ticked("error handling - redeem maps successful results") { implicit ticker =>
    assertCompleteAs(IO.unit.redeem(_ => 41, _ => 42), 42)
  }

  ticked("error handling - redeem catches exceptions thrown in recovery function") {
    implicit ticker =>
      case object TestException extends RuntimeException
      case object ThrownException extends RuntimeException
      assertCompleteAs(
        IO.raiseError[Unit](TestException).redeem(_ => throw ThrownException, _ => 42).attempt,
        Left(ThrownException))
  }

  ticked("error handling - redeem catches exceptions thrown in map function") {
    implicit ticker =>
      case object ThrownException extends RuntimeException
      assertCompleteAs(
        IO.unit.redeem(_ => 41, _ => throw ThrownException).attempt,
        Left(ThrownException))
  }

  ticked("error handling - redeemWith correctly recovers from errors") { implicit ticker =>
    case object TestException extends RuntimeException
    assertCompleteAs(
      IO.raiseError[Unit](TestException).redeemWith(_ => IO.pure(42), _ => IO.pure(43)),
      42)
  }

  ticked("error handling - recover correctly recovers from errors") { implicit ticker =>
    case object TestException extends RuntimeException
    assertCompleteAs(IO.raiseError[Int](TestException).recover { case TestException => 42 }, 42)
  }

  ticked("error handling - recoverWith correctly recovers from errors") { implicit ticker =>
    case object TestException extends RuntimeException
    assertCompleteAs(
      IO.raiseError[Int](TestException).recoverWith { case TestException => IO.pure(42) },
      42)
  }

  ticked("error handling - recoverWith does not recover from unmatched errors") {
    implicit ticker =>
      case object UnmatchedException extends RuntimeException
      case object ThrownException extends RuntimeException
      assertCompleteAs(
        IO.raiseError[Int](ThrownException)
          .recoverWith { case UnmatchedException => IO.pure(42) }
          .attempt,
        Left(ThrownException))
  }

  ticked("error handling - recover does not recover from unmatched errors") { implicit ticker =>
    case object UnmatchedException extends RuntimeException
    case object ThrownException extends RuntimeException
    assertCompleteAs(
      IO.raiseError[Int](ThrownException).recover { case UnmatchedException => 42 }.attempt,
      Left(ThrownException))
  }

  ticked("error handling - redeemWith binds successful results") { implicit ticker =>
    assertCompleteAs(IO.unit.redeemWith(_ => IO.pure(41), _ => IO.pure(42)), 42)
  }

  ticked("error handling - redeemWith catches exceptions throw in recovery function") {
    implicit ticker =>
      case object TestException extends RuntimeException
      case object ThrownException extends RuntimeException
      assertCompleteAs(
        IO.raiseError[Unit](TestException)
          .redeemWith(_ => throw ThrownException, _ => IO.pure(42))
          .attempt,
        Left(ThrownException))
  }

  ticked("error handling - redeemWith catches exceptions thrown in bind function") {
    implicit ticker =>
      case object ThrownException extends RuntimeException
      assertCompleteAs(
        IO.unit.redeem(_ => IO.pure(41), _ => throw ThrownException).attempt,
        Left(ThrownException))
  }

  ticked("error handling - catch exceptions thrown in map functions") { implicit ticker =>
    case object TestException extends RuntimeException
    assertCompleteAs(IO.unit.map(_ => (throw TestException): Unit).attempt, Left(TestException))
  }

  ticked("error handling - catch exceptions thrown in flatMap functions") { implicit ticker =>
    case object TestException extends RuntimeException
    assertCompleteAs(
      IO.unit.flatMap(_ => (throw TestException): IO[Unit]).attempt,
      Left(TestException))
  }

  ticked("error handling - catch exceptions thrown in handleErrorWith functions") {
    implicit ticker =>
      case object TestException extends RuntimeException
      case object WrongException extends RuntimeException
      assertCompleteAs(
        IO.raiseError[Unit](WrongException)
          .handleErrorWith(_ => (throw TestException): IO[Unit])
          .attempt,
        Left(TestException))
  }

  ticked("error handling - raise first bracket release exception if use effect succeeded") {
    implicit ticker =>
      case object TestException extends RuntimeException
      case object WrongException extends RuntimeException
      val io =
        IO.unit
          .bracket { _ => IO.unit.bracket(_ => IO.unit)(_ => IO.raiseError(TestException)) }(
            _ => IO.raiseError(WrongException))
      assertCompleteAs(io.attempt, Left(TestException))
  }

  ticked("error handling - report unhandled failure to the execution context") {
    implicit ticker =>
      case object TestException extends RuntimeException

      val action = IO.executionContext flatMap { ec =>
        IO defer {
          var ts: List[Throwable] = Nil

          val ec2 = new ExecutionContext {
            def reportFailure(t: Throwable) = ts ::= t
            def execute(r: Runnable) = ec.execute(r)
          }

          IO.raiseError(TestException).start.evalOn(ec2) *> IO.sleep(10.millis) *> IO(ts)
        }
      }

      assertCompleteAs(action, List(TestException))
  }

  ticked("error handling - not report observed failures to the execution context") {
    implicit ticker =>
      case object TestException extends RuntimeException

      val action = IO.executionContext flatMap { ec =>
        IO defer {
          var ts: List[Throwable] = Nil

          val ec2 = new ExecutionContext {
            def reportFailure(t: Throwable) = ts ::= t
            def execute(r: Runnable) = ec.execute(r)
          }

          for {
            f <- (IO.sleep(10.millis) *> IO.raiseError(TestException)).start.evalOn(ec2)
            _ <- f.join
            back <- IO(ts)
          } yield back
        }
      }

      assertCompleteAs(action, Nil)
  }

  // https://github.com/typelevel/cats-effect/issues/2962
  ticked("error handling - not report failures in timeout") { implicit ticker =>
    case object TestException extends RuntimeException

    val action = IO.executionContext flatMap { ec =>
      IO defer {
        var ts: List[Throwable] = Nil

        val ec2 = new ExecutionContext {
          def reportFailure(t: Throwable) = ts ::= t
          def execute(r: Runnable) = ec.execute(r)
        }

        for {
          f <- (IO.sleep(10.millis) *> IO
            .raiseError(TestException)
            .timeoutTo(1.minute, IO.pure(42))).start.evalOn(ec2)
          _ <- f.join
          back <- IO(ts)
        } yield back
      }
    }

    assertCompleteAs(action, Nil)
  }

  ticked("error handling - report errors raised during unsafeRunAndForget") { implicit ticker =>
    import cats.effect.unsafe.IORuntime
    import scala.concurrent.Promise

    def ec2(ec1: ExecutionContext, er: Promise[Boolean]) = new ExecutionContext {
      def reportFailure(t: Throwable) = er.success(true)
      def execute(r: Runnable) = ec1.execute(r)
    }

    val test = for {
      ec <- IO.executionContext
      errorReporter <- IO(Promise[Boolean]())
      customRuntime = IORuntime.builder().setCompute(ec2(ec, errorReporter), () => ()).build()
      _ <- IO(IO.raiseError(new RuntimeException).unsafeRunAndForget()(customRuntime))
      reported <- IO.fromFuture(IO(errorReporter.future))
    } yield reported
    assertCompleteAs(test, true)
  }

  //
  // suspension of side effects
  //

  ticked("suspension of side effects - suspend a side-effect without memoizing") {
    implicit ticker =>
      var i = 42

      val ioa = IO {
        i += 1
        i
      }

      assertCompleteAs(ioa, 43)
      assertCompleteAs(ioa, 44)
  }

  ticked("result in a null if lifting a pure null value") { implicit ticker =>
    // convoluted in order to avoid scalac warnings
    assertCompleteAs(IO.pure(null).map(_.asInstanceOf[Any]).map(_ == null), true)
  }

  ticked("result in a null if delaying a null value") { implicit ticker =>
    assertCompleteAs(IO(null).map(_.asInstanceOf[Any]).map(_ == null), true)
    assertCompleteAs(IO.delay(null).map(_.asInstanceOf[Any]).map(_ == null), true)
  }

  ticked("result in an NPE if deferring a null IO") { implicit ticker =>
    assertCompleteAs(
      IO.defer(null).attempt.map(_.left.toOption.get.isInstanceOf[NullPointerException]),
      true)
  }

  //
  // Fibers
  //

  ticked("fibers - start and join on a successful fiber") { implicit ticker =>
    assertCompleteAs(
      IO.pure(42).map(_ + 1).start.flatMap(_.join),
      Outcome.succeeded[IO, Throwable, Int](IO.pure(43)))
  }

  ticked("fibers - start and join on a failed fiber") { implicit ticker =>
    case object TestException extends RuntimeException
    assertCompleteAs(
      IO.raiseError[Unit](TestException).start.flatMap(_.join),
      Outcome.errored[IO, Throwable, Unit](TestException))
  }

  ticked("fibers - start and ignore a non-terminating fiber") { implicit ticker =>
    assertCompleteAs(IO.never.start.as(42), 42)
  }

  ticked("fibers - start a fiber then continue with its results") { implicit ticker =>
    assertCompleteAs(
      IO.pure(42).start.flatMap(_.join).flatMap { oc =>
        oc.fold(IO.pure(0), _ => IO.pure(-1), ioa => ioa)
      },
      42)
  }

  ticked("fibers - joinWithNever on a canceled fiber") { implicit ticker =>
    assertNonTerminate(for {
      fib <- IO.sleep(2.seconds).start
      _ <- fib.cancel
      _ <- fib.joinWithNever
    } yield ())
  }

  ticked("fibers - joinWithNever on a successful fiber") { implicit ticker =>
    assertCompleteAs(
      for {
        fib <- IO.pure(1).start
        res <- fib.joinWithNever
      } yield res,
      1)
  }

  ticked("fibers - joinWithNever on a failed fiber") { implicit ticker =>
    case object TestException extends RuntimeException
    assertFailAs(
      for {
        fib <- IO.raiseError[Unit](TestException).start
        res <- fib.joinWithNever
      } yield res,
      TestException)
  }

  ticked("fibers - preserve contexts through start") { implicit ticker =>
    val ec = ticker.ctx.derive()

    val ioa = for {
      f <- IO.executionContext.start.evalOn(ec)
      _ <- IO(ticker.ctx.tick())
      oc <- f.join
    } yield oc

    assertCompleteAs(ioa, Outcome.succeeded[IO, Throwable, ExecutionContext](IO.pure(ec)))
  }

  ticked("fibers - produce Canceled from start of canceled") { implicit ticker =>
    assertCompleteAs(IO.canceled.start.flatMap(_.join), Outcome.canceled[IO, Throwable, Unit])
  }

  ticked("fibers - cancel an already canceled fiber") { implicit ticker =>
    val test = for {
      f <- IO.canceled.start
      _ <- IO(ticker.ctx.tick())
      _ <- f.cancel
    } yield ()

    assertCompleteAs(test, ())
  }

  //
  // asyncCheckAttempt
  //

  ticked(
    "asyncCheckAttempt - resume value continuation within asyncCheckAttempt with immediate result") {
    implicit ticker => assertCompleteAs(IO.asyncCheckAttempt[Int](_ => IO(Right(42))), 42)
  }

  ticked(
    "asyncCheckAttempt - resume value continuation within asyncCheckAttempt with suspended result") {
    implicit ticker =>
      assertCompleteAs(IO.asyncCheckAttempt[Int](k => IO(k(Right(42))).as(Left(None))), 42)
  }

  ticked(
    "asyncCheckAttempt - continue from the results of an asyncCheckAttempt immediate result produced prior to registration") {
    implicit ticker =>
      val fa = IO.asyncCheckAttempt[Int](_ => IO(Right(42))).map(_ + 2)
      assertCompleteAs(fa, 44)
  }

  ticked(
    "asyncCheckAttempt - continue from the results of an asyncCheckAttempt suspended result produced prior to registration") {
    implicit ticker =>
      val fa = IO.asyncCheckAttempt[Int](cb => IO(cb(Right(42))).as(Left(None))).map(_ + 2)
      assertCompleteAs(fa, 44)
  }

  ticked(
    "asyncCheckAttempt - produce a failure when the registration raises an error after result"
  ) { implicit ticker =>
    case object TestException extends RuntimeException

    assertFailAs(
      IO.asyncCheckAttempt[Int](_ => IO(Right(42)).flatMap(_ => IO.raiseError(TestException)))
        .void,
      TestException)
  }

  ticked(
    "asyncCheckAttempt - produce a failure when the registration raises an error after callback"
  ) { implicit ticker =>
    case object TestException extends RuntimeException

    val fa = IO
      .asyncCheckAttempt[Int](cb =>
        IO(cb(Right(42))).flatMap(_ => IO.raiseError(TestException)))
      .void
    assertFailAs(fa, TestException)
  }

  ticked("asyncCheckAttempt - ignore asyncCheckAttempt callback") { implicit ticker =>
    case object TestException extends RuntimeException

    var cb: Either[Throwable, Int] => Unit = null

    val asyncCheckAttempt = IO.asyncCheckAttempt[Int] { cb0 =>
      IO { cb = cb0 } *> IO.pure(Right(42))
    }

    val test = for {
      fiber <- asyncCheckAttempt.start
      _ <- IO(ticker.ctx.tick())
      _ <- IO(cb(Right(43)))
      _ <- IO(ticker.ctx.tick())
      _ <- IO(cb(Left(TestException)))
      _ <- IO(ticker.ctx.tick())
      value <- fiber.joinWithNever
    } yield value

    assertCompleteAs(test, 42)
  }

  real("asyncCheckAttempt - ignore asyncCheckAttempt callback real") {
    case object TestException extends RuntimeException

    var cb: Either[Throwable, Int] => Unit = null

    val test = for {
      latch1 <- Deferred[IO, Unit]
      latch2 <- Deferred[IO, Unit]
      fiber <-
        IO.asyncCheckAttempt[Int] { cb0 =>
          IO { cb = cb0 } *> latch1.complete(()) *> latch2.get *> IO.pure(Right(42))
        }.start
      _ <- latch1.get
      _ <- IO(cb(Right(43)))
      _ <- IO(cb(Left(TestException)))
      _ <- latch2.complete(())
      value <- fiber.joinWithNever
    } yield value

    test.attempt.flatMap { n => IO(assertEquals(n, Right(42))) }
  }

  ticked("asyncCheckAttempt - repeated asyncCheckAttempt callback") { implicit ticker =>
    case object TestException extends RuntimeException

    var cb: Either[Throwable, Int] => Unit = null

    val asyncCheckAttempt = IO.asyncCheckAttempt[Int] { cb0 =>
      IO { cb = cb0 } *> IO.pure(Left(None))
    }

    val test = for {
      fiber <- asyncCheckAttempt.start
      _ <- IO(ticker.ctx.tick())
      _ <- IO(cb(Right(42)))
      _ <- IO(ticker.ctx.tick())
      _ <- IO(cb(Right(43)))
      _ <- IO(ticker.ctx.tick())
      _ <- IO(cb(Left(TestException)))
      _ <- IO(ticker.ctx.tick())
      value <- fiber.joinWithNever
    } yield value

    assertCompleteAs(test, 42)
  }

  real("asyncCheckAttempt - repeated asyncCheckAttempt callback real") {
    case object TestException extends RuntimeException

    var cb: Either[Throwable, Int] => Unit = null

    val test = for {
      latch1 <- Deferred[IO, Unit]
      latch2 <- Deferred[IO, Unit]
      fiber <-
        IO.asyncCheckAttempt[Int] { cb0 =>
          IO { cb = cb0 } *> latch1.complete(()) *> latch2.get *> IO.pure(Left(None))
        }.start
      _ <- latch1.get
      _ <- IO(cb(Right(42)))
      _ <- IO(cb(Right(43)))
      _ <- IO(cb(Left(TestException)))
      _ <- latch2.complete(())
      value <- fiber.joinWithNever
    } yield value

    test.attempt.flatMap { n => IO(assertEquals(n, Right(42))) }
  }

  ticked("asyncCheckAttempt - allow for misordered nesting") { implicit ticker =>
    var outerR = 0
    var innerR = 0

    val outer = IO.asyncCheckAttempt[Int] { cb1 =>
      val inner = IO.asyncCheckAttempt[Int] { cb2 =>
        IO(cb1(Right(1))) *>
          IO.executionContext.flatMap(ec => IO(ec.execute(() => cb2(Right(2))))).as(Left(None))
      }

      inner.flatMap(i => IO { innerR = i }).as(Left(None))
    }

    val test = outer.flatMap(i => IO { outerR = i })

    assertCompleteAs(test, ())
    assertEquals(outerR, 1)
    assertEquals(innerR, 2)
  }

  ticked("asyncCheckAttempt - be uncancelable if None finalizer") { implicit ticker =>
    val t = IO.asyncCheckAttempt[Int] { _ => IO.pure(Left(None)) }
    val test = for {
      fib <- t.start
      _ <- IO(ticker.ctx.tick())
      _ <- fib.cancel
    } yield ()

    assertNonTerminate(test)
  }

  //
  // async
  //

  ticked("async - resume value continuation within async") { implicit ticker =>
    assertCompleteAs(IO.async[Int](k => IO(k(Right(42))).map(_ => None)), 42)
  }

  ticked("async - continue from the results of an async produced prior to registration") {
    implicit ticker =>
      assertCompleteAs(IO.async[Int](cb => IO(cb(Right(42))).as(None)).map(_ + 2), 44)
  }

      // format: off
      ticked("async - produce a failure when the registration raises an error after callback") { implicit ticker =>
        case object TestException extends RuntimeException

        assertFailAs(IO.async[Int](cb => IO(cb(Right(42)))
          .flatMap(_ => IO.raiseError(TestException)))
          .void, TestException)
      }
      // format: on

  ticked("async - repeated async callback") { implicit ticker =>
    case object TestException extends RuntimeException

    var cb: Either[Throwable, Int] => Unit = null

    val async = IO.async_[Int] { cb0 => cb = cb0 }

    val test = for {
      fiber <- async.start
      _ <- IO(ticker.ctx.tick())
      _ <- IO(cb(Right(42)))
      _ <- IO(ticker.ctx.tick())
      _ <- IO(cb(Right(43)))
      _ <- IO(ticker.ctx.tick())
      _ <- IO(cb(Left(TestException)))
      _ <- IO(ticker.ctx.tick())
      value <- fiber.joinWithNever
    } yield value

    assertCompleteAs(test, 42)
  }

  real("async - repeated async callback real") {
    case object TestException extends RuntimeException

    var cb: Either[Throwable, Int] => Unit = null

    val test = for {
      latch1 <- Deferred[IO, Unit]
      latch2 <- Deferred[IO, Unit]
      fiber <-
        IO.async[Int] { cb0 =>
          IO { cb = cb0 } *> latch1.complete(()) *> latch2.get *> IO.pure(None)
        }.start
      _ <- latch1.get
      _ <- IO(cb(Right(42)))
      _ <- IO(cb(Right(43)))
      _ <- IO(cb(Left(TestException)))
      _ <- latch2.complete(())
      value <- fiber.joinWithNever
    } yield value

    test.attempt.flatMap { n => IO(assertEquals(n, Right(42))) }
  }

  ticked("async - calling async callback with null during registration (ticked)") {
    implicit ticker =>
      val test = IO.async[Int] { cb => IO(cb(null)).as(None) }.map(_ + 1).attempt.map { e =>
        assertCompleteAs(
          IO {
            e match {
              case Left(err) => assert(err.isInstanceOf[NullPointerException])
              case Right(v) => fail(s"Expected Left, got $v")
            }
          },
          ()
        )
      }

      assertCompleteAs(test, ())
  }

  ticked("async - calling async callback with null after registration (ticked)") {
    implicit ticker =>
      val test = for {
        cbp <- Deferred[IO, Either[Throwable, Int] => Unit]
        fib <- IO.async[Int] { cb => cbp.complete(cb).as(None) }.start
        _ <- IO(ticker.ctx.tickAll())
        cb <- cbp.get
        _ <- IO(ticker.ctx.tickAll())
        _ <- IO(cb(null))
        e <- fib.joinWithNever.attempt
        _ <- IO {
          e match {
            case Left(e) => assert(e.isInstanceOf[NullPointerException])
            case Right(v) => fail(s"Expected Left, got $v")
          }
        }
      } yield ()

      assertCompleteAs(test, ())
  }

  real("async - calling async callback with null during registration (real)") {
    IO.async[Int] { cb => IO(cb(null)).as(None) }.map(_ + 1).attempt.flatMap { e =>
      IO {
        e match {
          case Left(e) => assert(e.isInstanceOf[NullPointerException])
          case Right(v) => fail(s"Expected Left, got $v")
        }
      }
    }
  }

  real("async - calling async callback with null after registration (real)") {
    for {
      cbp <- Deferred[IO, Either[Throwable, Int] => Unit]
      latch <- Deferred[IO, Unit]
      fib <- IO.async[Int] { cb => cbp.complete(cb) *> latch.get.as(None) }.start
      cb <- cbp.get
      _r <- IO.both(
        latch.complete(()) *> IO.sleep(0.1.second) *> IO(cb(null)),
        fib.joinWithNever.attempt
      )
      (_, r) = _r
      _ <- IO {
        r match {
          case Left(e) => assert(e.isInstanceOf[NullPointerException])
          case Right(v) => fail(s"Expected Left, got $v")
        }
      }
    } yield ()
  }

  ticked("async - complete a fiber with Canceled under finalizer on poll") { implicit ticker =>
    val ioa =
      IO.uncancelable(p => IO.canceled >> p(IO.unit).guarantee(IO.unit)).start.flatMap(_.join)

    assertCompleteAs(ioa, Outcome.canceled[IO, Throwable, Unit])
  }

  real("async - invoke multiple joins on fiber completion") {
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
        IO(assert(result.isSuccess)).flatMap { _ =>
          result match {
            case Outcome.Succeeded(ioa) =>
              ioa.flatMap { oc =>
                IO(assert(result.isSuccess)).flatMap { _ =>
                  oc match {
                    case Outcome.Succeeded(ioa) =>
                      ioa flatMap { i => IO(assertEquals(i, 42)) }

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

  ticked("async - both - succeed if both sides succeed") { implicit ticker =>
    assertCompleteAs(IO.both(IO.pure(1), IO.pure(2)), (1, 2))
  }

  ticked("async - both - fail if lhs fails") { implicit ticker =>
    case object TestException extends Throwable
    assertFailAs(IO.both(IO.raiseError(TestException), IO.pure(2)).void, TestException)
  }

  ticked("async - both - fail if rhs fails") { implicit ticker =>
    case object TestException extends Throwable
    assertFailAs(IO.both(IO.pure(2), IO.raiseError(TestException)).void, TestException)
  }

  ticked("async - both - cancel if lhs cancels") { implicit ticker =>
    assertCompleteAs(
      IO.both(IO.canceled, IO.unit).void.start.flatMap(_.join),
      Outcome.canceled[IO, Throwable, Unit])

  }

  ticked("async - both - cancel if rhs cancels") { implicit ticker =>
    assertCompleteAs(
      IO.both(IO.unit, IO.canceled).void.start.flatMap(_.join),
      Outcome.canceled[IO, Throwable, Unit])
  }

  ticked("async - both - non terminate if lhs never completes") { implicit ticker =>
    assertNonTerminate(IO.both(IO.never, IO.pure(1)).void)
  }

  ticked("async - both - non terminate if rhs never completes") { implicit ticker =>
    assertNonTerminate(IO.both(IO.pure(1), IO.never).void)
  }

  ticked("async - both - propagate cancelation") { implicit ticker =>
    assertCompleteAs(
      for {
        fiber <- IO.both(IO.never, IO.never).void.start
        _ <- IO(ticker.ctx.tick())
        _ <- fiber.cancel
        _ <- IO(ticker.ctx.tick())
        oc <- fiber.join
      } yield oc,
      Outcome.canceled[IO, Throwable, Unit]
    )
  }

  ticked("async - both - cancel both fibers") { implicit ticker =>
    assertCompleteAs(
      for {
        l <- Ref[IO].of(false)
        r <- Ref[IO].of(false)
        fiber <-
          IO.both(IO.never.onCancel(l.set(true)), IO.never.onCancel(r.set(true))).start
        _ <- IO(ticker.ctx.tick())
        _ <- fiber.cancel
        _ <- IO(ticker.ctx.tick())
        l2 <- l.get
        r2 <- r.get
      } yield l2 -> r2,
      true -> true
    )
  }

  ticked("async - bothOutcome - cancel") { implicit ticker =>
    assertCompleteAs(
      for {
        g1 <- IO.deferred[Unit]
        g2 <- IO.deferred[Unit]
        f <- IO
          .bothOutcome(g1.complete(()) *> IO.never[Unit], g2.complete(()) *> IO.never[Unit])
          .start
        _ <- g1.get
        _ <- g2.get
        _ <- f.cancel
      } yield (),
      ()
    )
  }

  ticked("async - raceOutcome - cancel both fibers") { implicit ticker =>
    assertCompleteAs(
      for {
        l <- Ref.of[IO, Boolean](false)
        r <- Ref.of[IO, Boolean](false)
        fiber <-
          IO.never[Int]
            .onCancel(l.set(true))
            .raceOutcome(IO.never[Int].onCancel(r.set(true)))
            .start
        _ <- IO(ticker.ctx.tick())
        _ <- fiber.cancel
        _ <- IO(ticker.ctx.tick())
        l2 <- l.get
        r2 <- r.get
      } yield l2 -> r2,
      true -> true
    )
  }

  ticked("async - race - succeed with faster side") { implicit ticker =>
    assertCompleteAs(IO.race(IO.sleep(10.minutes) >> IO.pure(1), IO.pure(2)), Right(2))
  }

  ticked("async - race - fail if lhs fails") { implicit ticker =>
    case object TestException extends Throwable
    assertFailAs(
      IO.race(IO.raiseError[Int](TestException), IO.sleep(10.millis) >> IO.pure(1)).void,
      TestException)
  }

  ticked("async - race - fail if rhs fails") { implicit ticker =>
    case object TestException extends Throwable
    assertFailAs(
      IO.race(IO.sleep(10.millis) >> IO.pure(1), IO.raiseError[Int](TestException)).void,
      TestException)
  }

  ticked("async - race - fail if lhs fails and rhs never completes") { implicit ticker =>
    case object TestException extends Throwable
    assertFailAs(IO.race(IO.raiseError[Int](TestException), IO.never).void, TestException)
  }

  ticked("async - race - fail if rhs fails and lhs never completes") { implicit ticker =>
    case object TestException extends Throwable
    assertFailAs(IO.race(IO.never, IO.raiseError[Int](TestException)).void, TestException)
  }

  ticked("async - race - succeed if lhs never completes") { implicit ticker =>
    assertCompleteAs(IO.race(IO.never[Int], IO.pure(2)), Right(2))
  }

  ticked("async - race - succeed if rhs never completes") { implicit ticker =>
    assertCompleteAs(IO.race(IO.pure(2), IO.never[Int]), Left(2))
  }

  ticked("async - race - cancel if both sides cancel") { implicit ticker =>
    assertCompleteAs(
      IO.both(IO.canceled, IO.canceled).void.start.flatMap(_.join),
      Outcome.canceled[IO, Throwable, Unit])
  }

  ticked("async - race - cancel if lhs cancels and rhs succeeds") { implicit ticker =>
    assertSelfCancel(IO.race(IO.canceled, IO.sleep(1.milli) *> IO.pure(1)).void)
  }

  ticked("async - race - cancel if rhs cancels and lhs succeeds") { implicit ticker =>
    assertSelfCancel(IO.race(IO.sleep(1.milli) *> IO.pure(1), IO.canceled).void)
  }

  ticked("async - race - cancel if lhs cancels and rhs fails") { implicit ticker =>
    case object TestException extends Throwable
    assertSelfCancel(
      IO.race(IO.canceled, IO.sleep(1.milli) *> IO.raiseError[Unit](TestException)).void)
  }

  ticked("async - race - cancel if rhs cancels and lhs fails") { implicit ticker =>
    case object TestException extends Throwable
    assertSelfCancel(
      IO.race(IO.sleep(1.milli) *> IO.raiseError[Unit](TestException), IO.canceled).void)
  }

  ticked("async - race - cancel both fibers") { implicit ticker =>
    assertCompleteAs(
      for {
        l <- Ref.of[IO, Boolean](false)
        r <- Ref.of[IO, Boolean](false)
        fiber <-
          IO.race(IO.never.onCancel(l.set(true)), IO.never.onCancel(r.set(true))).start
        _ <- IO(ticker.ctx.tick())
        _ <- fiber.cancel
        _ <- IO(ticker.ctx.tick())
        l2 <- l.get
        r2 <- r.get
      } yield l2 -> r2,
      true -> true
    )
  }

  ticked("async - race - evaluate a timeout using sleep and race") { implicit ticker =>
    assertCompleteAs(IO.race(IO.never[Unit], IO.sleep(2.seconds)), Right(()))
  }

  real("easync - race - valuate a timeout using sleep and race in real time") {
    IO.race(IO.never[Unit], IO.sleep(10.millis)).flatMap { res =>
      IO {
        assertEquals(res, Right(()))
      }
    }
  }

  real("async - race - immediately cancel when timing out canceled") {
    val program = IO.canceled.timeout(2.seconds)

    val test = TestControl.execute(program.start.flatMap(_.join)) flatMap { ctl =>
      ctl.tickFor(1.second) *> ctl.results
    }

    test flatMap { results =>
      IO {
        results match {
          case Some(Outcome.Succeeded(Outcome.Canceled())) => ()
          case other => fail(s"Expected Outcome.Succeeded(Outcome.Canceled), got $other")
        }
      }
    }
  }

  real("async - race - immediately cancel when timing out and forgetting canceled") {
    val program = IO.canceled.timeoutAndForget(2.seconds)
    val test = TestControl.execute(program.start.flatMap(_.join)) flatMap { ctl =>
      ctl.tickFor(1.second) *> ctl.results
    }

    test flatMap { results =>
      IO {
        results match {
          case Some(Outcome.Succeeded(Outcome.Canceled())) => ()
          case other => fail(s"Expected Outcome.Succeeded(Outcome.Canceled), got $other")
        }
      }
    }
  }

  real("async - race - timeout a suspended timeoutAndForget") {
    val program = IO.never.timeoutAndForget(2.seconds).timeout(1.second)
    val test = TestControl.execute(program.start.flatMap(_.join)) flatMap { ctl =>
      ctl.tickFor(1.second) *> ctl.results
    }

    test.flatMap(results => IO(assert(results.isDefined)))
  }

  ticked("async - race - return the left when racing against never") { implicit ticker =>
    assertCompleteAs(
      IO.pure(42).racePair(IO.never: IO[Unit]).map(_.left.toOption.map(_._1).get),
      Outcome.succeeded[IO, Throwable, Int](IO.pure(42)))
  }

  real("async - race - immediately cancel inner race when outer unit") {
    for {
      start <- IO.monotonic
      _ <- IO.race(IO.unit, IO.race(IO.never, IO.sleep(10.seconds)))
      end <- IO.monotonic

      result <- IO(assert((end - start) < 5.seconds))
    } yield result
  }

  ticked("async - allow for misordered nesting") { implicit ticker =>
    var outerR = 0
    var innerR = 0

    val outer = IO.async[Int] { cb1 =>
      val inner = IO.async[Int] { cb2 =>
        IO(cb1(Right(1))) *>
          IO.executionContext.flatMap(ec => IO(ec.execute(() => cb2(Right(2))))).as(None)
      }

      inner.flatMap(i => IO { innerR = i }).as(None)
    }

    val test = outer.flatMap(i => IO { outerR = i })

    assertCompleteAs(test, ())
    assertEquals(outerR, 1)
    assertEquals(innerR, 2)
  }

  ticked("cancelation - implement never with non-terminating semantics") { implicit ticker =>
    assertNonTerminate(IO.never)
  }

  ticked("cancelation - cancel an infinite chain of right-binds") { implicit ticker =>
    lazy val infinite: IO[Unit] = IO.unit.flatMap(_ => infinite)
    assertCompleteAs(
      infinite.start.flatMap(f => f.cancel >> f.join),
      Outcome.canceled[IO, Throwable, Unit])
  }

  ticked("cancelation - cancel never") { implicit ticker =>
    assertCompleteAs(
      (IO.never: IO[Unit]).start.flatMap(f => f.cancel >> f.join),
      Outcome.canceled[IO, Throwable, Unit])
  }

  ticked("cancelation - cancel never after scheduling") { implicit ticker =>
    val ioa = for {
      f <- (IO.never: IO[Unit]).start
      ec <- IO.executionContext
      _ <- IO(ec.asInstanceOf[TestContext].tick())
      _ <- f.cancel
      oc <- f.join
    } yield oc

    assertCompleteAs(ioa, Outcome.canceled[IO, Throwable, Unit])
  }

  ticked("cancelation - sequence async cancel token upon cancelation during suspension") {
    implicit ticker =>
      var affected = false

      val target = IO.async[Unit] { _ => IO.pure(Some(IO { affected = true })) }

      val ioa = for {
        f <- target.start
        _ <- IO(ticker.ctx.tick())
        _ <- f.cancel
      } yield ()

      assertCompleteAs(ioa, ())
      assert(affected)
  }

  ticked("cancelation - suppress async cancel token upon cancelation in masked region") {
    implicit ticker =>
      var affected = false

      val target = IO uncancelable { _ =>
        IO.async[Unit] { _ => IO.pure(Some(IO { affected = true })) }
      }

      val ioa = for {
        f <- target.start
        _ <- IO(ticker.ctx.tick())
        _ <- f.cancel
      } yield ()

      assertNonTerminate(ioa) // we're canceling an uncancelable never
      assert(!affected)
  }

  ticked("cancelation - cancel flatMap continuations following a canceled uncancelable block") {
    implicit ticker =>
      assertSelfCancel(IO.uncancelable(_ => IO.canceled).flatMap(_ => IO.pure(())))
  }

  ticked("cancelation - sequence onCancel when canceled before registration") {
    implicit ticker =>
      var passed = false
      val test = IO.uncancelable { poll =>
        IO.canceled >> poll(IO.unit).onCancel(IO { passed = true })
      }

      assertSelfCancel(test)
      assert(passed)
  }

  ticked("cancelation - break out of uncancelable when canceled before poll") {
    implicit ticker =>
      var passed = true
      val test = IO.uncancelable { poll =>
        IO.canceled >> poll(IO.unit) >> IO { passed = false }
      }

      assertSelfCancel(test)
      assert(passed)
  }

  ticked("cancelation - not invoke onCancel when previously canceled within uncancelable") {
    implicit ticker =>
      var failed = false
      assertSelfCancel(
        IO.uncancelable(_ => IO.canceled >> IO.unit.onCancel(IO { failed = true })))
      assert(!failed)
  }

  ticked("cancelation - support re-enablement via cancelable") { implicit ticker =>
    assertCompleteAs(
      IO.deferred[Unit].flatMap { gate =>
        val test = IO.deferred[Unit] flatMap { latch =>
          (gate.complete(()) *> latch.get).uncancelable.cancelable(latch.complete(()).void)
        }

        test.start.flatMap(gate.get *> _.cancel)
      },
      ()
    )
  }

  ticked("cancelation - cancelable waits for termination") { implicit ticker =>
    def test(fin: IO[Unit]) = {
      val go = IO.never.uncancelable.cancelable(fin)
      go.start.flatMap(IO.sleep(1.second) *> _.cancel)
    }

    assertNonTerminate(test(IO.unit))
    assertNonTerminate(test(IO.raiseError(new Exception)))
    assertNonTerminate(test(IO.canceled))
  }

  ticked("cancelation - cancelable cancels task") { implicit ticker =>
    def test(fin: IO[Unit]) =
      IO.deferred[Unit].flatMap { latch =>
        val go = IO.never[Unit].onCancel(latch.complete(()).void).cancelable(fin)
        go.start.flatMap(IO.sleep(1.second) *> _.cancel) *> latch.get
      }

    assertCompleteAs(test(IO.unit), ())
    assertCompleteAs(test(IO.raiseError(new Exception)), ())
    assertCompleteAs(test(IO.canceled), ())
  }

  ticked("cancelation - only unmask within current fiber") { implicit ticker =>
    var passed = false
    val test = IO uncancelable { poll =>
      IO.uncancelable(_ => poll(IO.canceled >> IO { passed = true })).start.flatMap(_.join).void
    }

    assertCompleteAs(test, ())
    assert(passed)
  }

  ticked("cancelation - polls from unrelated fibers are no-ops") { implicit ticker =>
    var canceled = false
    val test = for {
      deferred <- Deferred[IO, Poll[IO]]
      started <- Deferred[IO, Unit]
      _ <- IO.uncancelable(deferred.complete).void.start
      f <- (started.complete(()) *>
        deferred.get.flatMap(poll => poll(IO.never[Unit]).onCancel(IO { canceled = true })))
        .uncancelable
        .start
      _ <- started.get
      _ <- f.cancel
    } yield ()

    assertNonTerminate(test)
    assert(!canceled)
  }

  ticked("cancelation - run three finalizers when an async is canceled while suspended") {
    implicit ticker =>
      var results = List[Int]()

      val body = IO.async[Nothing] { _ => IO.pure(Some(IO(results ::= 3))) }

      val test = for {
        f <- body.onCancel(IO(results ::= 2)).onCancel(IO(results ::= 1)).start
        _ <- IO(ticker.ctx.tick())
        _ <- f.cancel
        back <- IO(results)
      } yield back

      assertCompleteAs(test, List(1, 2, 3))
  }

  ticked("cancelation - uncancelable canceled with finalizer within fiber should not block") {
    implicit ticker =>
      val fab = IO.uncancelable(_ => IO.canceled.onCancel(IO.unit)).start.flatMap(_.join)

      assertCompleteAs(fab, Outcome.succeeded[IO, Throwable, Unit](IO.unit))
  }

  ticked(
    "cancelation - uncancelable canceled with finalizer within fiber should flatMap another day") {
    implicit ticker =>
      val fa = IO.pure(42)
      val fab: IO[Int => Int] =
        IO.uncancelable(_ => IO.canceled.onCancel(IO.unit))
          .start
          .flatMap(_.join)
          .flatMap(_ => IO.pure((i: Int) => i))

      assertCompleteAs(fab.ap(fa), 42)
      assertCompleteAs(fab.flatMap(f => fa.map(f)), 42)
  }

  ticked("cancelation - ignore repeated polls") { implicit ticker =>
    var passed = true

    val test = IO.uncancelable { poll =>
      poll(poll(IO.unit) >> IO.canceled) >> IO { passed = false }
    }

    assertSelfCancel(test)
    assert(passed)
  }

  ticked("cancelation - never terminate when racing infinite cancels") { implicit ticker =>
    var started = false

    val markStarted = IO { started = true }
    lazy val cedeUntilStarted: IO[Unit] =
      IO(started).ifM(IO.unit, IO.cede >> cedeUntilStarted)

    val test = for {
      f <- (markStarted *> IO.never).onCancel(IO.never).start
      _ <- cedeUntilStarted
      _ <- IO.race(f.cancel, f.cancel)
    } yield ()

    assertNonTerminate(test)
  }

  ticked("cancelation - first canceller backpressures subsequent cancellers") {
    implicit ticker =>
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

      assertNonTerminate(test)
  }

  real("cancelation - reliably cancel infinite IO.unit(s)") {
    IO.unit.foreverM.start.flatMap(f => IO.sleep(50.millis) >> f.cancel)
  }

  real("cancelation - reliably cancel infinite IO.cede(s)") {
    IO.cede.foreverM.start.flatMap(f => IO.sleep(50.millis) >> f.cancel)
  }

  real("cancelation - cancel a long sleep with a short one") {
    IO.sleep(10.seconds).race(IO.sleep(50.millis)).flatMap { res =>
      IO {
        assertEquals(res, Right(()))
      }
    }
  }

  real("cancelation - cancel a long sleep with a short one through evalOn") {
    IO.executionContext flatMap { ec =>
      val ec2 = new ExecutionContext {
        def execute(r: Runnable) = ec.execute(r)
        def reportFailure(t: Throwable) = ec.reportFailure(t)
      }

      val ioa = IO.sleep(10.seconds).race(IO.sleep(50.millis))
      ioa.evalOn(ec2) flatMap { res => IO(assertEquals(res, Right(()))) }
    }
  }

  ticked("cancelation - await uncancelable blocks in cancelation") { implicit ticker =>
    var started = false

    val markStarted = IO { started = true }
    lazy val cedeUntilStarted: IO[Unit] =
      IO(started).ifM(IO.unit, IO.cede >> cedeUntilStarted)

    assertNonTerminate(
      IO.uncancelable(_ => markStarted *> IO.never)
        .start
        .flatMap(f => cedeUntilStarted *> f.cancel))
  }

  ticked("cancelation - await cancelation of cancelation of uncancelable never") {
    implicit ticker =>
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

      assertNonTerminate(test)
  }

  ticked("cancelation - catch stray exceptions in uncancelable") { implicit ticker =>
    assertCompleteAs(IO.uncancelable[Unit](_ => throw new RuntimeException).voidError, ())
  }

  ticked("cancelation - unmask following stray exceptions in uncancelable") { implicit ticker =>
    assertSelfCancel(
      IO.uncancelable[Unit](_ => throw new RuntimeException)
        .handleErrorWith(_ => IO.canceled *> IO.never))
  }

  ticked("cancelation - catch exceptions in cont") { implicit ticker =>
    assertCompleteAs(
      IO.cont[Unit, Unit](new Cont[IO, Unit, Unit] {
        override def apply[F[_]](implicit F: MonadCancel[F, Throwable])
            : (Either[Throwable, Unit] => Unit, F[Unit], cats.effect.IO ~> F) => F[Unit] = {
          (_, _, _) => throw new Exception
        }
      }).voidError,
      ()
    )
  }

  ticked("finalization - mapping something with a finalizer should complete") {
    implicit ticker => assertCompleteAs(IO.pure(42).onCancel(IO.unit).as(()), ())
  }

  ticked("finalization - run an identity finalizer") { implicit ticker =>
    var affected = false

    assertCompleteAs(IO.unit.guaranteeCase { case _ => IO { affected = true } }, ())

    assert(affected)
  }

  ticked("finalization - run an identity finalizer and continue") { implicit ticker =>
    var affected = false

    val seed = IO.unit.guaranteeCase { case _ => IO { affected = true } }

    assertCompleteAs(seed.as(42), 42)

    assert(affected)
  }

  ticked("finalization - run multiple nested finalizers on cancel") { implicit ticker =>
    var inner = false
    var outer = false

    assertSelfCancel(IO.canceled.guarantee(IO { inner = true }).guarantee(IO { outer = true }))

    assert(inner)
    assert(outer)
  }

  ticked("finalization - run multiple nested finalizers on completion exactly once") {
    implicit ticker =>
      var inner = 0
      var outer = 0

      assertCompleteAs(IO.unit.guarantee(IO(inner += 1)).guarantee(IO(outer += 1)), ())

      assertEquals(inner, 1)
      assertEquals(outer, 1)
  }

  ticked("finalization - invoke onCase finalizer when cancelable async returns") {
    implicit ticker =>
      var passed = false

      // convenient proxy for an async that returns a cancelToken
      val test = IO.sleep(1.day) guaranteeCase {
        case Outcome.Succeeded(_) => IO { passed = true }
        case _ => IO.unit
      }

      assertCompleteAs(test, ())
      assert(passed)
  }

  ticked("finalization - hold onto errors through multiple finalizers") { implicit ticker =>
    case object TestException extends RuntimeException
    assertFailAs(
      IO.raiseError(TestException).guarantee(IO.unit).guarantee(IO.unit),
      TestException)
  }

  ticked("finalization - cede unit in a finalizer") { implicit ticker =>
    val body = IO.sleep(1.second).start.flatMap(_.join).map(_ => 42)
    assertCompleteAs(body.guarantee(IO.cede.map(_ => ())), 42)
  }

  ticked(
    "finalization - ensure async callback is suppressed during suspension of async finalizers") {
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
        _ <- IO(ticker.ctx.tick()) // schedule everything
        _ <- f.cancel.start
        _ <- IO(ticker.ctx.tick()) // get inside the finalizer suspension
        _ <- IO(cb(Right(())))
        _ <- IO(ticker.ctx.tick()) // show that the finalizer didn't explode
      } yield ()

      assertCompleteAs(test, ()) // ...but not throw an exception
  }

  ticked("finalization - run the continuation of an async finalizer within async") {
    implicit ticker =>
      var success = false

      val target = IO.async[Unit] { _ =>
        val fin = IO.async_[Unit] { cb => ticker.ctx.execute(() => cb(Right(()))) } *> IO {
          success = true
        }

        IO.pure(Some(fin))
      }

      val test = target.start flatMap { f => IO(ticker.ctx.tick()) *> f.cancel }

      assertCompleteAs(test, ())
      assert(success)
  }

      // format: off
      ticked("finalization - not finalize after uncancelable with suppressed cancelation (succeeded)") { implicit ticker =>
        var finalized = false

        val test =
          IO.uncancelable(_ => IO.canceled >> IO.pure(42))
            .onCancel(IO { finalized = true })
            .void

        assertSelfCancel(test )
        assert(!finalized)
      }
      // format: on

      // format: off
      ticked("finalization - not finalize after uncancelable with suppressed cancelation (errored)") { implicit ticker =>
        case object TestException extends RuntimeException

        var finalized = false

        val test =
          IO.uncancelable(_ => IO.canceled >> IO.raiseError(TestException))
            .onCancel(IO { finalized = true })
            .void

        assertSelfCancel(test )
        assert(!finalized)
      }
      // format: on

  ticked("finalization - finalize on uncaught errors in bracket use clauses") {
    implicit ticker =>
      val test = for {
        ref <- Ref[IO].of(false)
        _ <-
          IO.asyncForIO
            .bracketFull[Unit, Unit](_ => IO.unit)(_ => sys.error("borked!")) {
              case _ =>
                ref.set(true)
            }
            .attempt
        flag <- ref.get
      } yield flag

      assertCompleteAs(test, true)
  }

  ticked("stack-safety - evaluate 10,000 consecutive map continuations") { implicit ticker =>
    def loop(i: Int): IO[Unit] =
      if (i < 10000)
        IO.unit.flatMap(_ => loop(i + 1)).map(u => u)
      else
        IO.unit

    assertCompleteAs(loop(0), ())
  }

  ticked("stack-safety - evaluate 10,000 consecutive handleErrorWith continuations") {
    implicit ticker =>
      def loop(i: Int): IO[Unit] =
        if (i < 10000)
          IO.unit.flatMap(_ => loop(i + 1)).handleErrorWith(IO.raiseError(_))
        else
          IO.unit

      assertCompleteAs(loop(0), ())
  }

  ticked("stack-safety - evaluate 10,000 consecutive attempt continuations") {
    implicit ticker =>
      var acc: IO[Any] = IO.unit

      var j = 0
      while (j < 10000) {
        acc = acc.attempt
        j += 1
      }

      assertCompleteAs(acc.void, ())
  }

  real("parTraverseN - throw when n < 1") {
    IO.defer {
      List.empty[Int].parTraverseN(0)(_.pure[IO])
    }.mustFailWith[IllegalArgumentException]
  }

  real("parTraverseN - propagate errors") {
    List(1, 2, 3)
      .parTraverseN(2) { (n: Int) =>
        if (n == 2) IO.raiseError(new RuntimeException) else n.pure[IO]
      }
      .mustFailWith[RuntimeException]
  }

  ticked("parTraverseN - be cancelable") { implicit ticker =>
    val p = for {
      f <- List(1, 2, 3).parTraverseN(2)(_ => IO.never).start
      _ <- IO.sleep(100.millis)
      _ <- f.cancel
    } yield true

    assertCompleteAs(p, true)
  }

  real("parTraverseN_ - throw when n < 1") {
    IO.defer {
      List.empty[Int].parTraverseN_(0)(_.pure[IO])
    }.mustFailWith[IllegalArgumentException]
  }

  real("parTraverseN_ - propagate errors") {
    List(1, 2, 3)
      .parTraverseN_(2) { (n: Int) =>
        if (n == 2) IO.raiseError(new RuntimeException) else n.pure[IO]
      }
      .mustFailWith[RuntimeException]
  }

  ticked("parTraverseN_ - be cancelable") { implicit ticker =>
    val p = for {
      f <- List(1, 2, 3).parTraverseN_(2)(_ => IO.never).start
      _ <- IO.sleep(100.millis)
      _ <- f.cancel
    } yield true

    assertCompleteAs(p, true)
  }

  real("parallel - run parallel actually in parallel") {
    val x = IO.sleep(2.seconds) >> IO.pure(1)
    val y = IO.sleep(2.seconds) >> IO.pure(2)

    List(x, y).parSequence.timeout(3.seconds).flatMap { res =>
      IO {
        assertEquals(res, List(1, 2))
      }
    }
  }

  ticked("parallel - short-circuit on error") { implicit ticker =>
    case object TestException extends RuntimeException

    assertFailAs(
      (IO.never[Unit], IO.raiseError[Unit](TestException)).parTupled.void,
      TestException)
    assertFailAs(
      (IO.raiseError[Unit](TestException), IO.never[Unit]).parTupled.void,
      TestException)
  }

  ticked("parallel - short-circuit on canceled") { implicit ticker =>
    assertCompleteAs(
      (IO.never[Unit], IO.canceled).parTupled.start.flatMap(_.join.map(_.isCanceled)),
      true)
    assertCompleteAs(
      (IO.canceled, IO.never[Unit]).parTupled.start.flatMap(_.join.map(_.isCanceled)),
      true)
  }

  ticked("parallel - run finalizers when canceled") { implicit ticker =>
    val tsk = IO.ref(0).flatMap { ref =>
      val t = IO.never[Unit].onCancel(ref.update(_ + 1))
      for {
        fib <- (t, t).parTupled.start
        _ <- IO { ticker.ctx.tickAll() }
        _ <- fib.cancel
        c <- ref.get
      } yield c
    }

    assertCompleteAs(tsk, 2)
  }

  ticked(
    "parallel - run right side finalizer when canceled (and left side already completed)") {
    implicit ticker =>
      val tsk = IO.ref(0).flatMap { ref =>
        for {
          fib <- (IO.unit, IO.never[Unit].onCancel(ref.update(_ + 1))).parTupled.start
          _ <- IO { ticker.ctx.tickAll() }
          _ <- fib.cancel
          c <- ref.get
        } yield c
      }

      assertCompleteAs(tsk, 1)
  }

  ticked(
    "parallel - run left side finalizer when canceled (and right side already completed)") {
    implicit ticker =>
      val tsk = IO.ref(0).flatMap { ref =>
        for {
          fib <- (IO.never[Unit].onCancel(ref.update(_ + 1)), IO.unit).parTupled.start
          _ <- IO { ticker.ctx.tickAll() }
          _ <- fib.cancel
          c <- ref.get
        } yield c
      }

      assertCompleteAs(tsk, 1)
  }

  ticked("parallel - complete if both sides complete") { implicit ticker =>
    val tsk = (
      IO.sleep(2.seconds).as(20),
      IO.sleep(3.seconds).as(22)
    ).parTupled.map { case (l, r) => l + r }

    assertCompleteAs(tsk, 42)
  }

  ticked("parallel - not run forever on chained product") { implicit ticker =>
    import cats.effect.kernel.Par.ParallelF

    case object TestException extends RuntimeException

    val fa: IO[String] = IO.pure("a")
    val fb: IO[String] = IO.pure("b")
    val fc: IO[Unit] = IO.raiseError[Unit](TestException)
    val tsk =
      ParallelF.value(ParallelF(fa).product(ParallelF(fb)).product(ParallelF(fc))).void
    assertFailAs(tsk, TestException)
  }

  ticked("miscellaneous - round trip non-canceled through s.c.Future") { implicit ticker =>
    forAll { (ioa: IO[Int]) =>
      val normalized = ioa.onCancel(IO.never)
      normalized eqv IO.fromFuture(IO(normalized.unsafeToFuture()))
    }
  }

  ticked("miscellaneous - round trip cancelable through s.c.Future") { implicit ticker =>
    forAll { (ioa: IO[Int]) =>
      ioa eqv IO
        .fromFutureCancelable(
          IO(ioa.unsafeToFutureCancelable()).map {
            case (fut, fin) => (fut, IO.fromFuture(IO(fin())))
          }
        )
        .recoverWith { case _: CancellationException => IO.canceled *> IO.never[Int] }
    }
  }

  ticked("miscellaneous - canceled through s.c.Future is errored") { implicit ticker =>
    val test =
      IO.fromFuture(IO(IO.canceled.as(-1).unsafeToFuture())).handleError(_ => 42)

    assertCompleteAs(test, 42)
  }

  ticked("miscellaneous - run a synchronous IO") { implicit ticker =>
    val ioa = IO(1).map(_ + 2)
    val test = IO.fromFuture(IO(ioa.unsafeToFuture()))
    assertCompleteAs(test, 3)
  }

  ticked("miscellaneous - run an asynchronous IO") { implicit ticker =>
    val ioa = (IO(1) <* IO.cede).map(_ + 2)
    val test = IO.fromFuture(IO(ioa.unsafeToFuture()))
    assertCompleteAs(test, 3)
  }

  ticked("miscellaneous - run several IOs back to back") { implicit ticker =>
    var counter = 0
    val increment = IO {
      counter += 1
    }

    val num = 10

    val test = IO.fromFuture(IO(increment.unsafeToFuture())).replicateA(num).void

    assertCompleteAs(test.flatMap(_ => IO(counter)), num)
  }

  ticked("miscellaneous - run multiple IOs in parallel") { implicit ticker =>
    val num = 10

    val test = for {
      latches <- (0 until num).toList.traverse(_ => Deferred[IO, Unit])
      awaitAll = latches.parTraverse_(_.get)

      // engineer a deadlock: all subjects must be run in parallel or this will hang
      subjects = latches.map(latch => latch.complete(()) >> awaitAll)

      _ <- subjects.parTraverse_(act => IO(act.unsafeRunAndForget()))
    } yield ()

    assertCompleteAs(test, ())
  }

  ticked("miscellaneous - forward cancelation onto the inner action") { implicit ticker =>
    var canceled = false

    val run = IO {
      IO.never.onCancel(IO { canceled = true }).unsafeRunCancelable()
    }

    val test = IO.defer {
      run.flatMap(ct => IO.sleep(500.millis) >> IO.fromFuture(IO(ct())))
    }

    assertCompleteAs(test.flatMap(_ => IO(canceled)), true)
  }

  ticked("temporal - sleep for ten seconds") { implicit ticker =>
    assertCompleteAs(IO.sleep(10.seconds).as(1), 1)
  }

  ticked("temporal - sleep for ten seconds and continue") { implicit ticker =>
    var affected = false
    assertCompleteAs(IO.sleep(10.seconds) >> IO { affected = true }, ())
    assert(affected)
  }

  real("temporal - round up negative sleeps") {
    IO.sleep(-1.seconds)
  }

  real("timeout - succeed") {
    val op = IO.pure(true).timeout(100.millis)

    op.flatMap { res =>
      IO {
        assert(res)
      }
    }
  }

  real("timeout - cancel a loop") {
    val loop = IO.cede.foreverM

    val op = loop.timeout(5.millis).attempt

    op.flatMap { res =>
      IO {
        res match {
          case Left(e) => assert(e.isInstanceOf[TimeoutException])
          case Right(_) => fail("Expected Left, got Right")
        }
      }
    }
  }

  real("timeout - invoke finalizers on timed out things") {
    for {
      ref <- Ref[IO].of(false)
      _ <- IO.never.onCancel(ref.set(true)).timeoutTo(50.millis, IO.unit)
      v <- ref.get
      r <- IO(assert(v))
    } yield r
  }

  ticked("timeout - non-terminate on an uncancelable fiber") { implicit ticker =>
    assertNonTerminate(IO.never.uncancelable.timeout(1.second))
  }

  real("timeout - propagate successful result from a completed effect") {
    IO.pure(true).delayBy(50.millis).uncancelable.timeout(10.millis).map { res => assert(res) }
  }

  real("timeout - propagate error from a completed effect") {
    IO.raiseError(new RuntimeException)
      .delayBy(50.millis)
      .uncancelable
      .timeout(10.millis)
      .attempt
      .map {
        case Left(e) => assert(e.isInstanceOf[RuntimeException])
        case Right(_) => fail("Expected Left, got Right")
      }
  }

  real("timeoutTo - succeed") {
    val op =
      IO.pure(true).timeoutTo(5.millis, IO.raiseError(new RuntimeException))

    op.flatMap { res =>
      IO {
        assert(res)
      }
    }
  }

  real("timeoutTo - use fallback") {
    val loop = IO.cede.foreverM

    val op = loop.timeoutTo(5.millis, IO.pure(true))

    op.flatMap { res =>
      IO {
        assert(res)
      }
    }
  }

  real("timeoutAndForget - terminate on an uncancelable fiber") {
    IO.never[Unit].uncancelable.timeoutAndForget(1.second).attempt flatMap { r =>
      IO {
        r match {
          case Left(e) => assert(e.isInstanceOf[TimeoutException])
          case Right(other) => fail(s"Expected Left, got $other")
        }
      }
    }
  }

  realWithRuntime("no-op when canceling an expired timer 1") { rt =>
    // this one excercises a timer removed via `TimerHeap#pollFirstIfTriggered`
    IO(Promise[Unit]()).flatMap { p =>
      IO(rt.scheduler.sleep(1.nanosecond, () => p.success(()))).flatMap { cancel =>
        IO.fromFuture(IO(p.future)) *> IO(cancel.run())
      }
    }
  }

  realWithRuntime("no-op when canceling an expired timer 2") { rt =>
    // this one excercises a timer removed via `TimerHeap#insert`
    IO(Promise[Unit]()).flatMap { p =>
      IO(rt.scheduler.sleep(1.nanosecond, () => p.success(()))).flatMap { cancel =>
        IO.sleep(1.nanosecond) *> IO.fromFuture(IO(p.future)) *> IO(cancel.run())
      }
    }
  }

  realWithRuntime("no-op when canceling a timer twice") { rt =>
    IO(rt.scheduler.sleep(1.day, () => ())).flatMap(cancel =>
      IO(cancel.run()) *> IO(cancel.run()))
  }

  testUnit("syncStep - run sync IO to completion") {
    var bool = false

    val zero = 0

    val io = IO.pure(5).flatMap { n =>
      IO.delay {
        2
      }.map { m => n * m }
        .flatMap { result =>
          IO {
            bool = result % 2 == 0
          }
        }
        .map { _ => n / zero }
        .handleErrorWith { t => IO.raiseError(t) }
        .attempt
        .flatMap { _ => IO.pure(42) }
    }

    assertCompleteAsSync(
      io.syncStep(1024).map {
        case Left(_) => throw new RuntimeException("Boom!")
        case Right(n) => n
      },
      42)

    assertEquals(bool, true)
  }

  testUnit("syncStep - fail synchronously with a throwable") {
    case object TestException extends RuntimeException
    val io = IO.raiseError[Unit](TestException)

    assertFailAsSync(
      io.syncStep(1024).map {
        case Left(_) => throw new RuntimeException("Boom!")
        case Right(()) => ()
      },
      TestException)
  }

  ticked("syncStep - evaluate side effects until the first async boundary and nothing else") {
    implicit ticker =>
      var inDelay = false
      var inMap = false
      var inAsync = false
      var inFlatMap = false

      val io = IO
        .delay {
          inDelay = true
        }
        .map { _ => inMap = true }
        .flatMap { _ =>
          IO.async_[Unit] { cb =>
            inAsync = true
            cb(Right(()))
          }
        }
        .flatMap { _ =>
          IO {
            inFlatMap = true
          }
        }

      assertCompleteAsSync(
        io.syncStep(1024).flatMap {
          case Left(remaining) =>
            SyncIO.delay {
              assert(inDelay)
              assert(inMap)
              assert(!inAsync)
              assert(!inFlatMap)

              assertCompleteAs(remaining, ())
              assert(inAsync)
              assert(inFlatMap)
              ()
            }

          case Right(_) => SyncIO.raiseError[Unit](new RuntimeException("Boom!"))
        },
        ()
      )
  }

  testUnit("syncStep - evaluate up to limit and no further") {
    var first = false
    var second = false

    val program = IO { first = true } *> IO { second = true }

    val test = program.syncStep(2) flatMap { results =>
      SyncIO {
        assert(first)
        assert(!second)
        assert(results.isLeft)

        ()
      }
    }

    assertCompleteAsSync(test, ())
  }

  ticked("syncStep - should not execute effects twice for map (#2858)") { implicit ticker =>
    var i = 0
    val io = (IO(i += 1) *> IO.cede).void.syncStep(Int.MaxValue).unsafeRunSync() match {
      case Left(io) => io
      case Right(_) => IO.unit
    }
    assertCompleteAs(io, ())
    assertEquals(i, 1)
  }

  ticked("syncStep - should not execute effects twice for flatMap (#2858)") { implicit ticker =>
    var i = 0
    val io =
      (IO(i += 1) *> IO.cede *> IO.unit).syncStep(Int.MaxValue).unsafeRunSync() match {
        case Left(io) => io
        case Right(_) => IO.unit
      }
    assertCompleteAs(io, ())
    assertEquals(i, 1)
  }

  ticked("syncStep - should not execute effects twice for attempt (#2858)") { implicit ticker =>
    var i = 0
    val io =
      (IO(i += 1) *> IO.cede).attempt.void.syncStep(Int.MaxValue).unsafeRunSync() match {
        case Left(io) => io
        case Right(_) => IO.unit
      }
    assertCompleteAs(io, ())
    assertEquals(i, 1)
  }

  ticked("syncStep - should not execute effects twice for handleErrorWith (#2858)") {
    implicit ticker =>
      var i = 0
      val io = (IO(i += 1) *> IO.cede)
        .handleErrorWith(_ => IO.unit)
        .syncStep(Int.MaxValue)
        .unsafeRunSync() match {
        case Left(io) => io
        case Right(_) => IO.unit
      }
      assertCompleteAs(io, ())
      assertEquals(i, 1)
  }

  testUnit("syncStep - handle uncancelable") {
    val sio = IO.unit.uncancelable.syncStep(Int.MaxValue)
    assertCompleteAsSync(sio.map(_.bimap(_ => (), _ => ())), Right(()))
  }

  testUnit("syncStep - handle onCancel") {
    val sio = IO.unit.onCancel(IO.unit).syncStep(Int.MaxValue)
    assertCompleteAsSync(sio.map(_.bimap(_ => (), _ => ())), Right(()))
  }

  testUnit("syncStep - synchronously allocate a vanilla resource") {
    val sio =
      Resource.make(IO.unit)(_ => IO.unit).allocated.map(_._1).syncStep(Int.MaxValue)
    assertCompleteAsSync(sio.map(_.bimap(_ => (), _ => ())), Right(()))
  }

  testUnit("syncStep - synchronously allocate a evalMapped resource") {
    val sio = Resource
      .make(IO.unit)(_ => IO.unit)
      .evalMap(_ => IO.unit)
      .allocated
      .map(_._1)
      .syncStep(Int.MaxValue)
    assertCompleteAsSync(sio.map(_.bimap(_ => (), _ => ())), Right(()))
  }

  real("fiber repeated yielding test") {
    def yieldUntil(ref: Ref[IO, Boolean]): IO[Unit] =
      ref.get.flatMap(b => if (b) IO.unit else IO.cede *> yieldUntil(ref))

    for {
      n <- IO(java.lang.Runtime.getRuntime.availableProcessors)
      done <- Ref.of[IO, Boolean](false)
      fibers <- List.range(0, n - 1).traverse(_ => yieldUntil(done).start)
      _ <- IO.unit.start.replicateA(200)
      _ <- done.set(true).start
      _ <- IO.unit.start.replicateA(1000)
      _ <- yieldUntil(done)
      _ <- fibers.traverse(_.join)
    } yield ()
  }

  property("serialize") {
    forAll { (io: IO[Int]) => serializable(io) }(
      implicitly,
      arbitraryIOWithoutContextShift,
      implicitly,
      implicitly)
  }

  real("produce a specialized version of Deferred") {
    IO.deferred[Unit].flatMap(d => IO(d.isInstanceOf[IODeferred[?]]))
  }

  platformTests()

  {
    implicit val ticker = Ticker()

    checkAll(
      "IO",
      AsyncTests[IO].async[Int, Int, Int](10.millis)
    ) /*(Parameters(seed = Some(Seed.fromBase64("ZxDXpm7_3Pdkl-Fvt8M90Cxfam9wKuzcifQ1QsIJxND=").get)))*/
  }

  {
    implicit val ticker = Ticker()

    checkAll(
      "IO[Int]",
      MonoidTests[IO[Int]].monoid
    ) /*(Parameters(seed = Some(Seed.fromBase64("_1deH2u9O-z6PmkYMBgZT-3ofsMEAMStR9x0jKlFgyO=").get)))*/
  }

  {
    implicit val ticker = Ticker()

    checkAll(
      "IO[Int]",
      SemigroupKTests[IO].semigroupK[Int]
    )
  }

  {
    implicit val ticker = Ticker()

    checkAll(
      "IO",
      AlignTests[IO].align[Int, Int, Int, Int]
    )
  }

}
