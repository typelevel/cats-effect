/*
 * Copyright 2020-2023 Typelevel
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

import org.scalacheck.Prop
import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.{CancellationException, ExecutionContext, TimeoutException}
import scala.concurrent.duration._

import Prop.forAll

class IOSpec extends BaseSpec with Discipline with IOPlatformSpecification {

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

      "preserve monad identity on asyncCheckAttempt immediate result" in ticked {
        implicit ticker =>
          val fa = IO.asyncCheckAttempt[Int](_ => IO(Right(42)))
          fa.flatMap(i => IO.pure(i)) must completeAs(42)
          fa must completeAs(42)
      }

      "preserve monad identity on asyncCheckAttempt suspended result" in ticked {
        implicit ticker =>
          val fa = IO.asyncCheckAttempt[Int](cb => IO(cb(Right(42))).as(Left(None)))
          fa.flatMap(i => IO.pure(i)) must completeAs(42)
          fa must completeAs(42)
      }

      "preserve monad identity on async" in ticked { implicit ticker =>
        val fa = IO.async[Int](cb => IO(cb(Right(42))).as(None))
        fa.flatMap(i => IO.pure(i)) must completeAs(42)
        fa must completeAs(42)
      }
    }

    "error handling" should {
      "capture errors in suspensions" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO(throw TestException) must failAs(TestException)
      }

      "resume error continuation within asyncCheckAttempt" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO.asyncCheckAttempt[Unit](k => IO(k(Left(TestException))).as(Left(None))) must failAs(
          TestException)
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

      "orElse must return other if previous IO Fails" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        (IO.raiseError[Int](TestException) orElse IO.pure(42)) must completeAs(42)
      }

      "Return current IO if successful" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        (IO.pure(42) orElse IO.raiseError[Int](TestException)) must completeAs(42)
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

      "rethrow is inverse of attempt" in ticked { implicit ticker =>
        forAll { (io: IO[Int]) => io.attempt.rethrow eqv io }
      }

      "redeem is flattened redeemWith" in ticked { implicit ticker =>
        forAll { (io: IO[Int], recover: Throwable => IO[String], bind: Int => IO[String]) =>
          io.redeem(recover, bind).flatten eqv io.redeemWith(recover, bind)
        }
      }

      "redeem subsumes handleError" in ticked { implicit ticker =>
        forAll { (io: IO[Int], recover: Throwable => Int) =>
          // we have to workaround functor law weirdness here... again... sigh... because of self-cancelation
          io.redeem(recover, identity).flatMap(IO.pure(_)) eqv io.handleError(recover)
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

      "recover correctly recovers from errors" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO.raiseError[Int](TestException).recover { case TestException => 42 } must completeAs(
          42)
      }

      "recoverWith correctly recovers from errors" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        IO.raiseError[Int](TestException).recoverWith {
          case TestException => IO.pure(42)
        } must completeAs(42)
      }

      "recoverWith does not recover from unmatched errors" in ticked { implicit ticker =>
        case object UnmatchedException extends RuntimeException
        case object ThrownException extends RuntimeException
        IO.raiseError[Int](ThrownException)
          .recoverWith { case UnmatchedException => IO.pure(42) }
          .attempt must completeAs(Left(ThrownException))
      }

      "recover does not recover from unmatched errors" in ticked { implicit ticker =>
        case object UnmatchedException extends RuntimeException
        case object ThrownException extends RuntimeException
        IO.raiseError[Int](ThrownException)
          .recover { case UnmatchedException => 42 }
          .attempt must completeAs(Left(ThrownException))
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

      "raise first bracket release exception if use effect succeeded" in ticked(
        implicit ticker => {
          case object TestException extends RuntimeException
          case object WrongException extends RuntimeException
          val io =
            IO.unit
              .bracket { _ =>
                IO.unit.bracket(_ => IO.unit)(_ => IO.raiseError(TestException))
              }(_ => IO.raiseError(WrongException))
          io.attempt must completeAs(Left(TestException))
        })

      "report unhandled failure to the execution context" in ticked { implicit ticker =>
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

        action must completeAs(List(TestException))
      }

      "not report observed failures to the execution context" in ticked { implicit ticker =>
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

        action must completeAs(Nil)
      }

      // https://github.com/typelevel/cats-effect/issues/2962
      "not report failures in timeout" in ticked { implicit ticker =>
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

        action must completeAs(Nil)
      }

      "report errors raised during unsafeRunAndForget" in ticked { implicit ticker =>
        import cats.effect.unsafe.IORuntime
        import scala.concurrent.Promise

        def ec2(ec1: ExecutionContext, er: Promise[Boolean]) = new ExecutionContext {
          def reportFailure(t: Throwable) = er.success(true)
          def execute(r: Runnable) = ec1.execute(r)
        }

        val test = for {
          ec <- IO.executionContext
          errorReporter <- IO(Promise[Boolean]())
          customRuntime = IORuntime
            .builder()
            .setCompute(ec2(ec, errorReporter), () => ())
            .build()
          _ <- IO(IO.raiseError(new RuntimeException).unsafeRunAndForget()(customRuntime))
          reported <- IO.fromFuture(IO(errorReporter.future))
        } yield reported
        test must completeAs(true)
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

      "result in a null if lifting a pure null value" in ticked { implicit ticker =>
        // convoluted in order to avoid scalac warnings
        IO.pure(null).map(_.asInstanceOf[Any]).map(_ == null) must completeAs(true)
      }

      "result in a null if delaying a null value" in ticked { implicit ticker =>
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
          Outcome.succeeded[IO, Throwable, Int](IO.pure(43)))
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

      "joinWithNever on a canceled fiber" in ticked { implicit ticker =>
        (for {
          fib <- IO.sleep(2.seconds).start
          _ <- fib.cancel
          _ <- fib.joinWithNever
        } yield ()) must nonTerminate
      }

      "joinWithNever on a successful fiber" in ticked { implicit ticker =>
        (for {
          fib <- IO.pure(1).start
          res <- fib.joinWithNever
        } yield res) must completeAs(1)
      }

      "joinWithNever on a failed fiber" in ticked { implicit ticker =>
        case object TestException extends RuntimeException
        (for {
          fib <- IO.raiseError[Unit](TestException).start
          res <- fib.joinWithNever
        } yield res) must failAs(TestException)
      }

      "preserve contexts through start" in ticked { implicit ticker =>
        val ec = ticker.ctx.derive()

        val ioa = for {
          f <- IO.executionContext.start.evalOn(ec)
          _ <- IO(ticker.ctx.tick())
          oc <- f.join
        } yield oc

        ioa must completeAs(Outcome.succeeded[IO, Throwable, ExecutionContext](IO.pure(ec)))
      }

      "produce Canceled from start of canceled" in ticked { implicit ticker =>
        IO.canceled.start.flatMap(_.join) must completeAs(Outcome.canceled[IO, Throwable, Unit])
      }

      "cancel an already canceled fiber" in ticked { implicit ticker =>
        val test = for {
          f <- IO.canceled.start
          _ <- IO(ticker.ctx.tick())
          _ <- f.cancel
        } yield ()

        test must completeAs(())
      }

    }

    "asyncCheckAttempt" should {

      "resume value continuation within asyncCheckAttempt with immediate result" in ticked {
        implicit ticker => IO.asyncCheckAttempt[Int](_ => IO(Right(42))) must completeAs(42)
      }

      "resume value continuation within asyncCheckAttempt with suspended result" in ticked {
        implicit ticker =>
          IO.asyncCheckAttempt[Int](k => IO(k(Right(42))).as(Left(None))) must completeAs(42)
      }

      "continue from the results of an asyncCheckAttempt immediate result produced prior to registration" in ticked {
        implicit ticker =>
          val fa = IO.asyncCheckAttempt[Int](_ => IO(Right(42))).map(_ + 2)
          fa must completeAs(44)
      }

      "continue from the results of an asyncCheckAttempt suspended result produced prior to registration" in ticked {
        implicit ticker =>
          val fa = IO.asyncCheckAttempt[Int](cb => IO(cb(Right(42))).as(Left(None))).map(_ + 2)
          fa must completeAs(44)
      }

      // format: off
      "produce a failure when the registration raises an error after result" in ticked { implicit ticker =>
        case object TestException extends RuntimeException

        IO.asyncCheckAttempt[Int](_ => IO(Right(42))
          .flatMap(_ => IO.raiseError(TestException)))
          .void must failAs(TestException)
      }
      // format: on

      // format: off
      "produce a failure when the registration raises an error after callback" in ticked { implicit ticker =>
        case object TestException extends RuntimeException

        val fa = IO.asyncCheckAttempt[Int](cb => IO(cb(Right(42)))
          .flatMap(_ => IO.raiseError(TestException)))
          .void
        fa must failAs(TestException)
      }
      // format: on

      "ignore asyncCheckAttempt callback" in ticked { implicit ticker =>
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

        test must completeAs(42)
      }

      "ignore asyncCheckAttempt callback real" in real {
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

        test.attempt.flatMap { n => IO(n mustEqual Right(42)) }
      }

      "repeated asyncCheckAttempt callback" in ticked { implicit ticker =>
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

        test must completeAs(42)
      }

      "repeated asyncCheckAttempt callback real" in real {
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

        test.attempt.flatMap { n => IO(n mustEqual Right(42)) }
      }

      "allow for misordered nesting" in ticked { implicit ticker =>
        var outerR = 0
        var innerR = 0

        val outer = IO.asyncCheckAttempt[Int] { cb1 =>
          val inner = IO.asyncCheckAttempt[Int] { cb2 =>
            IO(cb1(Right(1))) *>
              IO.executionContext
                .flatMap(ec => IO(ec.execute(() => cb2(Right(2)))))
                .as(Left(None))
          }

          inner.flatMap(i => IO { innerR = i }).as(Left(None))
        }

        val test = outer.flatMap(i => IO { outerR = i })

        test must completeAs(())
        outerR mustEqual 1
        innerR mustEqual 2
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
          _ <- IO(ticker.ctx.tick())
          _ <- IO(cb(Right(42)))
          _ <- IO(ticker.ctx.tick())
          _ <- IO(cb(Right(43)))
          _ <- IO(ticker.ctx.tick())
          _ <- IO(cb(Left(TestException)))
          _ <- IO(ticker.ctx.tick())
          value <- fiber.joinWithNever
        } yield value

        test must completeAs(42)
      }

      "repeated async callback real" in real {
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

        test.attempt.flatMap { n => IO(n mustEqual Right(42)) }
      }

      "calling async callback with null during registration (ticked)" in ticked {
        implicit ticker =>
          IO.async[Int] { cb => IO(cb(null)).as(None) }
            .map(_ + 1)
            .attempt
            .flatMap(e =>
              IO(e must beLeft(beAnInstanceOf[NullPointerException])).void) must completeAs(())
      }

      "calling async callback with null after registration (ticked)" in ticked {
        implicit ticker =>
          val test = for {
            cbp <- Deferred[IO, Either[Throwable, Int] => Unit]
            fib <- IO.async[Int] { cb => cbp.complete(cb).as(None) }.start
            _ <- IO(ticker.ctx.tickAll())
            cb <- cbp.get
            _ <- IO(ticker.ctx.tickAll())
            _ <- IO(cb(null))
            e <- fib.joinWithNever.attempt
            _ <- IO(e must beLeft(beAnInstanceOf[NullPointerException]))
          } yield ()

          test must completeAs(())
      }

      "calling async callback with null during registration (real)" in real {
        IO.async[Int] { cb => IO(cb(null)).as(None) }
          .map(_ + 1)
          .attempt
          .flatMap(e => IO(e must beLeft(beAnInstanceOf[NullPointerException])))
      }

      "calling async callback with null after registration (real)" in real {
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
          _ <- IO(r must beLeft(beAnInstanceOf[NullPointerException]))
        } yield ok
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
          IO.both(IO.canceled, IO.unit).void.start.flatMap(_.join) must completeAs(
            Outcome.canceled[IO, Throwable, Unit])

        }

        "cancel if rhs cancels" in ticked { implicit ticker =>
          IO.both(IO.unit, IO.canceled).void.start.flatMap(_.join) must completeAs(
            Outcome.canceled[IO, Throwable, Unit])
        }

        "non terminate if lhs never completes" in ticked { implicit ticker =>
          IO.both(IO.never, IO.pure(1)).void must nonTerminate
        }

        "non terminate if rhs never completes" in ticked { implicit ticker =>
          IO.both(IO.pure(1), IO.never).void must nonTerminate
        }

        "propagate cancelation" in ticked { implicit ticker =>
          (for {
            fiber <- IO.both(IO.never, IO.never).void.start
            _ <- IO(ticker.ctx.tick())
            _ <- fiber.cancel
            _ <- IO(ticker.ctx.tick())
            oc <- fiber.join
          } yield oc) must completeAs(Outcome.canceled[IO, Throwable, Unit])
        }

        "cancel both fibers" in ticked { implicit ticker =>
          (for {
            l <- Ref[IO].of(false)
            r <- Ref[IO].of(false)
            fiber <-
              IO.both(IO.never.onCancel(l.set(true)), IO.never.onCancel(r.set(true))).start
            _ <- IO(ticker.ctx.tick())
            _ <- fiber.cancel
            _ <- IO(ticker.ctx.tick())
            l2 <- l.get
            r2 <- r.get
          } yield l2 -> r2) must completeAs(true -> true)
        }

      }

      "bothOutcome" should {
        "cancel" in ticked { implicit ticker =>
          (for {
            g1 <- IO.deferred[Unit]
            g2 <- IO.deferred[Unit]
            f <- IO
              .bothOutcome(g1.complete(()) *> IO.never[Unit], g2.complete(()) *> IO.never[Unit])
              .start
            _ <- g1.get
            _ <- g2.get
            _ <- f.cancel
          } yield ()) must completeAs(())
        }
      }

      "raceOutcome" should {
        "cancel both fibers" in ticked { implicit ticker =>
          (for {
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
          } yield l2 -> r2) must completeAs(true -> true)
        }
      }

      "race" should {
        "succeed with faster side" in ticked { implicit ticker =>
          IO.race(IO.sleep(10.minutes) >> IO.pure(1), IO.pure(2)) must completeAs(Right(2))
        }

        "fail if lhs fails" in ticked { implicit ticker =>
          case object TestException extends Throwable
          IO.race(IO.raiseError[Int](TestException), IO.sleep(10.millis) >> IO.pure(1))
            .void must failAs(TestException)
        }

        "fail if rhs fails" in ticked { implicit ticker =>
          case object TestException extends Throwable
          IO.race(IO.sleep(10.millis) >> IO.pure(1), IO.raiseError[Int](TestException))
            .void must failAs(TestException)
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
          IO.both(IO.canceled, IO.canceled).void.start.flatMap(_.join) must completeAs(
            Outcome.canceled[IO, Throwable, Unit])
        }

        "cancel if lhs cancels and rhs succeeds" in ticked { implicit ticker =>
          IO.race(IO.canceled, IO.sleep(1.milli) *> IO.pure(1)).void must selfCancel
        }

        "cancel if rhs cancels and lhs succeeds" in ticked { implicit ticker =>
          IO.race(IO.sleep(1.milli) *> IO.pure(1), IO.canceled).void must selfCancel
        }

        "cancel if lhs cancels and rhs fails" in ticked { implicit ticker =>
          case object TestException extends Throwable
          IO.race(IO.canceled, IO.sleep(1.milli) *> IO.raiseError[Unit](TestException))
            .void must selfCancel
        }

        "cancel if rhs cancels and lhs fails" in ticked { implicit ticker =>
          case object TestException extends Throwable
          IO.race(IO.sleep(1.milli) *> IO.raiseError[Unit](TestException), IO.canceled)
            .void must selfCancel
        }

        "cancel both fibers" in ticked { implicit ticker =>
          (for {
            l <- Ref.of[IO, Boolean](false)
            r <- Ref.of[IO, Boolean](false)
            fiber <-
              IO.race(IO.never.onCancel(l.set(true)), IO.never.onCancel(r.set(true))).start
            _ <- IO(ticker.ctx.tick())
            _ <- fiber.cancel
            _ <- IO(ticker.ctx.tick())
            l2 <- l.get
            r2 <- r.get
          } yield l2 -> r2) must completeAs(true -> true)
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

        "immediately cancel when timing out canceled" in real {
          val program = IO.canceled.timeout(2.seconds)

          val test = TestControl.execute(program.start.flatMap(_.join)) flatMap { ctl =>
            ctl.tickFor(1.second) *> ctl.results
          }

          test flatMap { results =>
            IO {
              results must beLike { case Some(Outcome.Succeeded(Outcome.Canceled())) => ok }
            }
          }
        }

        "immediately cancel when timing out and forgetting canceled" in real {
          val program = IO.canceled.timeoutAndForget(2.seconds)
          val test = TestControl.execute(program.start.flatMap(_.join)) flatMap { ctl =>
            ctl.tickFor(1.second) *> ctl.results
          }

          test flatMap { results =>
            IO {
              results must beLike { case Some(Outcome.Succeeded(Outcome.Canceled())) => ok }
            }
          }
        }

        "timeout a suspended timeoutAndForget" in real {
          val program = IO.never.timeoutAndForget(2.seconds).timeout(1.second)
          val test = TestControl.execute(program.start.flatMap(_.join)) flatMap { ctl =>
            ctl.tickFor(1.second) *> ctl.results
          }

          test.flatMap(results => IO(results must beSome))
        }

        "return the left when racing against never" in ticked { implicit ticker =>
          IO.pure(42)
            .racePair(IO.never: IO[Unit])
            .map(_.left.toOption.map(_._1).get) must completeAs(
            Outcome.succeeded[IO, Throwable, Int](IO.pure(42)))
        }

        "immediately cancel inner race when outer unit" in real {
          for {
            start <- IO.monotonic
            _ <- IO.race(IO.unit, IO.race(IO.never, IO.sleep(10.seconds)))
            end <- IO.monotonic

            result <- IO((end - start) must beLessThan(5.seconds))
          } yield result
        }
      }

      "allow for misordered nesting" in ticked { implicit ticker =>
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

        test must completeAs(())
        outerR mustEqual 1
        innerR mustEqual 2
      }
    }

    "cancelation" should {

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
          _ <- IO(ec.asInstanceOf[TestContext].tick())
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
            _ <- IO(ticker.ctx.tick())
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
            _ <- IO(ticker.ctx.tick())
            _ <- f.cancel
          } yield ()

          ioa must nonTerminate // we're canceling an uncancelable never
          affected must beFalse
      }

      "cancel flatMap continuations following a canceled uncancelable block" in ticked {
        implicit ticker =>
          IO.uncancelable(_ => IO.canceled).flatMap(_ => IO.pure(())) must selfCancel
      }

      "sequence onCancel when canceled before registration" in ticked { implicit ticker =>
        var passed = false
        val test = IO.uncancelable { poll =>
          IO.canceled >> poll(IO.unit).onCancel(IO { passed = true })
        }

        test must selfCancel
        passed must beTrue
      }

      "break out of uncancelable when canceled before poll" in ticked { implicit ticker =>
        var passed = true
        val test = IO.uncancelable { poll =>
          IO.canceled >> poll(IO.unit) >> IO { passed = false }
        }

        test must selfCancel
        passed must beTrue
      }

      "not invoke onCancel when previously canceled within uncancelable" in ticked {
        implicit ticker =>
          var failed = false
          IO.uncancelable(_ =>
            IO.canceled >> IO.unit.onCancel(IO { failed = true })) must selfCancel
          failed must beFalse
      }

      "support re-enablement via cancelable" in ticked { implicit ticker =>
        IO.deferred[Unit].flatMap { gate =>
          val test = IO.deferred[Unit] flatMap { latch =>
            (gate.complete(()) *> latch.get).uncancelable.cancelable(latch.complete(()).void)
          }

          test.start.flatMap(gate.get *> _.cancel)
        } must completeAs(())
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

      "polls from unrelated fibers are no-ops" in ticked { implicit ticker =>
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

        test must nonTerminate
        canceled must beFalse
      }

      "run three finalizers when an async is canceled while suspended" in ticked {
        implicit ticker =>
          var results = List[Int]()

          val body = IO.async[Nothing] { _ => IO.pure(Some(IO(results ::= 3))) }

          val test = for {
            f <- body.onCancel(IO(results ::= 2)).onCancel(IO(results ::= 1)).start
            _ <- IO(ticker.ctx.tick())
            _ <- f.cancel
            back <- IO(results)
          } yield back

          test must completeAs(List(1, 2, 3))
      }

      "uncancelable canceled with finalizer within fiber should not block" in ticked {
        implicit ticker =>
          val fab = IO.uncancelable(_ => IO.canceled.onCancel(IO.unit)).start.flatMap(_.join)

          fab must completeAs(Outcome.succeeded[IO, Throwable, Unit](IO.unit))
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

        test must selfCancel
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

      "reliably cancel infinite IO.unit(s)" in real {
        IO.unit.foreverM.start.flatMap(f => IO.sleep(50.millis) >> f.cancel).as(ok)
      }

      "reliably cancel infinite IO.cede(s)" in real {
        IO.cede.foreverM.start.flatMap(f => IO.sleep(50.millis) >> f.cancel).as(ok)
      }

      "cancel a long sleep with a short one" in real {
        IO.sleep(10.seconds).race(IO.sleep(50.millis)).flatMap { res =>
          IO {
            res must beRight(())
          }
        }
      }

      "cancel a long sleep with a short one through evalOn" in real {
        IO.executionContext flatMap { ec =>
          val ec2 = new ExecutionContext {
            def execute(r: Runnable) = ec.execute(r)
            def reportFailure(t: Throwable) = ec.reportFailure(t)
          }

          val ioa = IO.sleep(10.seconds).race(IO.sleep(50.millis))
          ioa.evalOn(ec2) flatMap { res => IO(res must beRight(())) }
        }
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

      "catch stray exceptions in uncancelable" in ticked { implicit ticker =>
        IO.uncancelable[Unit](_ => throw new RuntimeException).voidError must completeAs(())
      }

      "unmask following stray exceptions in uncancelable" in ticked { implicit ticker =>
        IO.uncancelable[Unit](_ => throw new RuntimeException)
          .handleErrorWith(_ => IO.canceled *> IO.never) must selfCancel
      }
    }

    "finalization" should {

      "mapping something with a finalizer should complete" in ticked { implicit ticker =>
        IO.pure(42).onCancel(IO.unit).as(()) must completeAs(())
      }

      "run an identity finalizer" in ticked { implicit ticker =>
        var affected = false

        IO.unit.guaranteeCase { case _ => IO { affected = true } } must completeAs(())

        affected must beTrue
      }

      "run an identity finalizer and continue" in ticked { implicit ticker =>
        var affected = false

        val seed = IO.unit.guaranteeCase { case _ => IO { affected = true } }

        seed.as(42) must completeAs(42)

        affected must beTrue
      }

      "run multiple nested finalizers on cancel" in ticked { implicit ticker =>
        var inner = false
        var outer = false

        IO.canceled
          .guarantee(IO { inner = true })
          .guarantee(IO { outer = true }) must selfCancel

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
        val test = IO.sleep(1.day) guaranteeCase {
          case Outcome.Succeeded(_) => IO { passed = true }
          case _ => IO.unit
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
            _ <- IO(ticker.ctx.tick()) // schedule everything
            _ <- f.cancel.start
            _ <- IO(ticker.ctx.tick()) // get inside the finalizer suspension
            _ <- IO(cb(Right(())))
            _ <- IO(ticker.ctx.tick()) // show that the finalizer didn't explode
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

        val test = target.start flatMap { f => IO(ticker.ctx.tick()) *> f.cancel }

        test must completeAs(())
        success must beTrue
      }

      // format: off
      "not finalize after uncancelable with suppressed cancelation (succeeded)" in ticked { implicit ticker =>
        var finalized = false

        val test =
          IO.uncancelable(_ => IO.canceled >> IO.pure(42))
            .onCancel(IO { finalized = true })
            .void

        test must selfCancel
        finalized must beFalse
      }
      // format: on

      // format: off
      "not finalize after uncancelable with suppressed cancelation (errored)" in ticked { implicit ticker =>
        case object TestException extends RuntimeException

        var finalized = false

        val test =
          IO.uncancelable(_ => IO.canceled >> IO.raiseError(TestException))
            .onCancel(IO { finalized = true })
            .void

        test must selfCancel
        finalized must beFalse
      }
      // format: on

      "finalize on uncaught errors in bracket use clauses" in ticked { implicit ticker =>
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

        test must completeAs(true)
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

      "evaluate 10,000 consecutive attempt continuations" in ticked { implicit ticker =>
        var acc: IO[Any] = IO.unit

        var j = 0
        while (j < 10000) {
          acc = acc.attempt
          j += 1
        }

        acc.void must completeAs(())
      }

    }

    "parTraverseN" should {

      "throw when n < 1" in real {
        IO.defer {
          List.empty[Int].parTraverseN(0)(_.pure[IO])
        }.mustFailWith[IllegalArgumentException]
      }

      "propagate errors" in real {
        List(1, 2, 3)
          .parTraverseN(2) { (n: Int) =>
            if (n == 2) IO.raiseError(new RuntimeException) else n.pure[IO]
          }
          .mustFailWith[RuntimeException]
      }

      "be cancelable" in ticked { implicit ticker =>
        val p = for {
          f <- List(1, 2, 3).parTraverseN(2)(_ => IO.never).start
          _ <- IO.sleep(100.millis)
          _ <- f.cancel
        } yield true

        p must completeAs(true)
      }

    }

    "parallel" should {
      "run parallel actually in parallel" in real {
        val x = IO.sleep(2.seconds) >> IO.pure(1)
        val y = IO.sleep(2.seconds) >> IO.pure(2)

        List(x, y).parSequence.timeout(3.seconds).flatMap { res =>
          IO {
            res mustEqual List(1, 2)
          }
        }
      }

      "short-circuit on error" in ticked { implicit ticker =>
        case object TestException extends RuntimeException

        (IO.never[Unit], IO.raiseError[Unit](TestException)).parTupled.void must failAs(
          TestException)
        (IO.raiseError[Unit](TestException), IO.never[Unit]).parTupled.void must failAs(
          TestException)
      }

      "short-circuit on canceled" in ticked { implicit ticker =>
        (IO.never[Unit], IO.canceled)
          .parTupled
          .start
          .flatMap(_.join.map(_.isCanceled)) must completeAs(true)
        (IO.canceled, IO.never[Unit])
          .parTupled
          .start
          .flatMap(_.join.map(_.isCanceled)) must completeAs(true)
      }

      "run finalizers when canceled" in ticked { implicit ticker =>
        val tsk = IO.ref(0).flatMap { ref =>
          val t = IO.never[Unit].onCancel(ref.update(_ + 1))
          for {
            fib <- (t, t).parTupled.start
            _ <- IO { ticker.ctx.tickAll() }
            _ <- fib.cancel
            c <- ref.get
          } yield c
        }

        tsk must completeAs(2)
      }

      "run right side finalizer when canceled (and left side already completed)" in ticked {
        implicit ticker =>
          val tsk = IO.ref(0).flatMap { ref =>
            for {
              fib <- (IO.unit, IO.never[Unit].onCancel(ref.update(_ + 1))).parTupled.start
              _ <- IO { ticker.ctx.tickAll() }
              _ <- fib.cancel
              c <- ref.get
            } yield c
          }

          tsk must completeAs(1)
      }

      "run left side finalizer when canceled (and right side already completed)" in ticked {
        implicit ticker =>
          val tsk = IO.ref(0).flatMap { ref =>
            for {
              fib <- (IO.never[Unit].onCancel(ref.update(_ + 1)), IO.unit).parTupled.start
              _ <- IO { ticker.ctx.tickAll() }
              _ <- fib.cancel
              c <- ref.get
            } yield c
          }

          tsk must completeAs(1)
      }

      "complete if both sides complete" in ticked { implicit ticker =>
        val tsk = (
          IO.sleep(2.seconds).as(20),
          IO.sleep(3.seconds).as(22)
        ).parTupled.map { case (l, r) => l + r }

        tsk must completeAs(42)
      }

      "not run forever on chained product" in ticked { implicit ticker =>
        import cats.effect.kernel.Par.ParallelF

        case object TestException extends RuntimeException

        val fa: IO[String] = IO.pure("a")
        val fb: IO[String] = IO.pure("b")
        val fc: IO[Unit] = IO.raiseError[Unit](TestException)
        val tsk =
          ParallelF.value(ParallelF(fa).product(ParallelF(fb)).product(ParallelF(fc))).void
        tsk must failAs(TestException)
      }
    }

    "miscellaneous" should {

      "round trip non-canceled through s.c.Future" in ticked { implicit ticker =>
        forAll { (ioa: IO[Int]) =>
          val normalized = ioa.onCancel(IO.never)
          normalized eqv IO.fromFuture(IO(normalized.unsafeToFuture()))
        }
      }

      "round trip cancelable through s.c.Future" in ticked { implicit ticker =>
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

      "canceled through s.c.Future is errored" in ticked { implicit ticker =>
        val test =
          IO.fromFuture(IO(IO.canceled.as(-1).unsafeToFuture())).handleError(_ => 42)

        test must completeAs(42)
      }

      "run a synchronous IO" in ticked { implicit ticker =>
        val ioa = IO(1).map(_ + 2)
        val test = IO.fromFuture(IO(ioa.unsafeToFuture()))
        test must completeAs(3)
      }

      "run an asynchronous IO" in ticked { implicit ticker =>
        val ioa = (IO(1) <* IO.cede).map(_ + 2)
        val test = IO.fromFuture(IO(ioa.unsafeToFuture()))
        test must completeAs(3)
      }

      "run several IOs back to back" in ticked { implicit ticker =>
        var counter = 0
        val increment = IO {
          counter += 1
        }

        val num = 10

        val test = IO.fromFuture(IO(increment.unsafeToFuture())).replicateA(num).void

        test.flatMap(_ => IO(counter)) must completeAs(num)
      }

      "run multiple IOs in parallel" in ticked { implicit ticker =>
        val num = 10

        val test = for {
          latches <- (0 until num).toList.traverse(_ => Deferred[IO, Unit])
          awaitAll = latches.parTraverse_(_.get)

          // engineer a deadlock: all subjects must be run in parallel or this will hang
          subjects = latches.map(latch => latch.complete(()) >> awaitAll)

          _ <- subjects.parTraverse_(act => IO(act.unsafeRunAndForget()))
        } yield ()

        test must completeAs(())
      }

      "forward cancelation onto the inner action" in ticked { implicit ticker =>
        var canceled = false

        val run = IO {
          IO.never.onCancel(IO { canceled = true }).unsafeRunCancelable()
        }

        val test = IO.defer {
          run.flatMap(ct => IO.sleep(500.millis) >> IO.fromFuture(IO(ct())))
        }

        test.flatMap(_ => IO(canceled)) must completeAs(true)
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

      "round up negative sleeps" in real {
        IO.sleep(-1.seconds).as(ok)
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
              res must beLike { case Left(e) => e must haveClass[TimeoutException] }
            }
          }
        }

        "invoke finalizers on timed out things" in real {
          for {
            ref <- Ref[IO].of(false)
            _ <- IO.never.onCancel(ref.set(true)).timeoutTo(50.millis, IO.unit)
            v <- ref.get
            r <- IO(v must beTrue)
          } yield r
        }

        "non-terminate on an uncancelable fiber" in ticked { implicit ticker =>
          IO.never.uncancelable.timeout(1.second) must nonTerminate
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

      "timeoutAndForget" should {
        "terminate on an uncancelable fiber" in real {
          IO.never.uncancelable.timeoutAndForget(1.second).attempt flatMap { e =>
            IO {
              e must beLike { case Left(e) => e must haveClass[TimeoutException] }
            }
          }
        }
      }
    }

    "syncStep" should {
      "run sync IO to completion" in {
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

        io.syncStep(1024).map {
          case Left(_) => throw new RuntimeException("Boom!")
          case Right(n) => n
        } must completeAsSync(42)

        bool must beEqualTo(true)
      }

      "fail synchronously with a throwable" in {
        case object TestException extends RuntimeException
        val io = IO.raiseError[Unit](TestException)

        io.syncStep(1024).map {
          case Left(_) => throw new RuntimeException("Boom!")
          case Right(()) => ()
        } must failAsSync(TestException)
      }

      "evaluate side effects until the first async boundary and nothing else" in ticked {
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

          io.syncStep(1024).flatMap {
            case Left(remaining) =>
              SyncIO.delay {
                inDelay must beTrue
                inMap must beTrue
                inAsync must beFalse
                inFlatMap must beFalse

                remaining must completeAs(())
                inAsync must beTrue
                inFlatMap must beTrue
                ()
              }

            case Right(_) => SyncIO.raiseError[Unit](new RuntimeException("Boom!"))
          } must completeAsSync(())
      }

      "evaluate up to limit and no further" in {
        var first = false
        var second = false

        val program = IO { first = true } *> IO { second = true }

        val test = program.syncStep(2) flatMap { results =>
          SyncIO {
            first must beTrue
            second must beFalse
            results must beLeft

            ()
          }
        }

        test must completeAsSync(())
      }

      "should not execute effects twice for map (#2858)" in ticked { implicit ticker =>
        var i = 0
        val io = (IO(i += 1) *> IO.cede).void.syncStep(Int.MaxValue).unsafeRunSync() match {
          case Left(io) => io
          case Right(_) => IO.unit
        }
        io must completeAs(())
        i must beEqualTo(1)
      }

      "should not execute effects twice for flatMap (#2858)" in ticked { implicit ticker =>
        var i = 0
        val io =
          (IO(i += 1) *> IO.cede *> IO.unit).syncStep(Int.MaxValue).unsafeRunSync() match {
            case Left(io) => io
            case Right(_) => IO.unit
          }
        io must completeAs(())
        i must beEqualTo(1)
      }

      "should not execute effects twice for attempt (#2858)" in ticked { implicit ticker =>
        var i = 0
        val io =
          (IO(i += 1) *> IO.cede).attempt.void.syncStep(Int.MaxValue).unsafeRunSync() match {
            case Left(io) => io
            case Right(_) => IO.unit
          }
        io must completeAs(())
        i must beEqualTo(1)
      }

      "should not execute effects twice for handleErrorWith (#2858)" in ticked {
        implicit ticker =>
          var i = 0
          val io = (IO(i += 1) *> IO.cede)
            .handleErrorWith(_ => IO.unit)
            .syncStep(Int.MaxValue)
            .unsafeRunSync() match {
            case Left(io) => io
            case Right(_) => IO.unit
          }
          io must completeAs(())
          i must beEqualTo(1)
      }

      "handle uncancelable" in {
        val sio = IO.unit.uncancelable.syncStep(Int.MaxValue)
        sio.map(_.bimap(_ => (), _ => ())) must completeAsSync(Right(()))
      }

      "handle onCancel" in {
        val sio = IO.unit.onCancel(IO.unit).syncStep(Int.MaxValue)
        sio.map(_.bimap(_ => (), _ => ())) must completeAsSync(Right(()))
      }

      "synchronously allocate a vanilla resource" in {
        val sio =
          Resource.make(IO.unit)(_ => IO.unit).allocated.map(_._1).syncStep(Int.MaxValue)
        sio.map(_.bimap(_ => (), _ => ())) must completeAsSync(Right(()))
      }

      "synchronously allocate a evalMapped resource" in {
        val sio = Resource
          .make(IO.unit)(_ => IO.unit)
          .evalMap(_ => IO.unit)
          .allocated
          .map(_._1)
          .syncStep(Int.MaxValue)
        sio.map(_.bimap(_ => (), _ => ())) must completeAsSync(Right(()))
      }
    }

    "fiber repeated yielding test" in real {
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
        res <- IO(ok)
      } yield res
    }

    "serialize" in {
      forAll { (io: IO[Int]) => serializable(io) }(
        implicitly,
        arbitraryIOWithoutContextShift,
        implicitly,
        implicitly)
    }

    "produce a specialized version of Deferred" in real {
      IO.deferred[Unit].flatMap(d => IO(d must haveClass[IODeferred[_]]))
    }

    platformSpecs
  }

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
