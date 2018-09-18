/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats.effect.concurrent

import scala.concurrent.{ExecutionContext, Future}

import cats.effect.{ContextShift, IO, Timer}
import org.scalatest.{AsyncFunSuite, Matchers, Succeeded}
import cats.implicits._
import scala.concurrent.duration._

import catalysts.Platform

class CircuitBreakerTests extends AsyncFunSuite with Matchers {
  private val Tries = if (Platform.isJvm) 10000 else 5000

  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global
  /*_*/
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  /*_*/
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)


  private def mkBreaker() = CircuitBreaker[IO].of(
    maxFailures = 5,
    resetTimeout = 1.minute
  ).unsafeRunSync()


  test("should work for successful async IOs") {
    val circuitBreaker = mkBreaker()

    var effect = 0
    val task = circuitBreaker.protect(IO {
      effect += 1
    } *> IO.shift)

    List.fill(Tries)(task).sequence_.unsafeToFuture().map { _ =>
      effect shouldBe Tries
    }
  }

  test("should work for successful immediate tasks") {
    val circuitBreaker = mkBreaker()

    var effect = 0
    val task = circuitBreaker.protect(IO {
      effect += 1
    })

    List.fill(Tries)(task).sequence_.unsafeToFuture().map { _ =>
      effect shouldBe Tries
    }
  }

  test("should be stack safe for successful async tasks (flatMap)") {
    val circuitBreaker = mkBreaker()

    def loop(n: Int, acc: Int): IO[Int] = {
      if (n > 0)
        circuitBreaker.protect(IO(acc+1) <* IO.shift)
          .flatMap(s => loop(n-1, s))
      else
        IO.pure(acc)
    }

    loop(Tries, 0).unsafeToFuture().map { value =>
      value shouldBe Tries
    }
  }

  test("should be stack safe for successful async tasks (suspend)") {
    val circuitBreaker = mkBreaker()

    def loop(n: Int, acc: Int): IO[Int] =
      IO.suspend {
        if (n > 0)
          circuitBreaker.protect(loop(n-1, acc+1))
        else
          IO.pure(acc)
      } <* IO.shift

    loop(Tries, 0).unsafeToFuture().map { value =>
      value shouldBe Tries
    }
  }

  test("should be stack safe for successful immediate tasks (flatMap)") {
    val circuitBreaker = mkBreaker()

    def loop(n: Int, acc: Int): IO[Int] = {
      if (n > 0)
        circuitBreaker.protect(IO(acc+1))
          .flatMap(s => loop(n-1, s))
      else
        IO.pure(acc)
    }

    loop(Tries, 0).unsafeToFuture().map { value =>
      value shouldBe Tries
    }
  }

  test("should be stack safe for successful immediate tasks (suspend)") {
    val circuitBreaker = mkBreaker()

    def loop(n: Int, acc: Int): IO[Int] =
      IO.suspend {
        if (n > 0)
          circuitBreaker.protect(loop(n-1, acc+1))
        else
          IO.pure(acc)
      }

    loop(Tries, 0).unsafeToFuture().map { value =>
      value shouldBe Tries
    }
  }

  test("complete workflow with failures and exponential backoff") {
    var openedCount = 0
    var closedCount = 0
    var halfOpenCount = 0
    var rejectedCount = 0

    val circuitBreaker = {
      val cb = CircuitBreaker[IO].of(
        maxFailures = 5,
        resetTimeout = 200.millis,
        exponentialBackoffFactor = 2,
        maxResetTimeout = 1.second
      ).unsafeRunSync()

      cb.doOnOpen(IO { openedCount += 1})
        .doOnClosed(IO { closedCount += 1 })
        .doOnHalfOpen(IO { halfOpenCount += 1 })
        .doOnRejected(IO { rejectedCount += 1 })
    }

    val dummy = new RuntimeException("dummy")
    val taskInError = circuitBreaker.protect(IO[Int](throw dummy))
    val taskSuccess = circuitBreaker.protect(IO { 1 })
    val fa =
      for {
        _ <- taskInError.attempt
        _ <- taskInError.attempt
        _ = circuitBreaker.unsafeState() shouldBe CircuitBreaker.Closed(2)
        // A successful value should reset the counter
        _ <- taskSuccess
        _ = circuitBreaker.unsafeState() shouldBe CircuitBreaker.Closed(0)

        _ <- taskInError.attempt.replicateA(5)
        _ = circuitBreaker.unsafeState() should matchPattern {
          case CircuitBreaker.Open(_, t) if t == 200.millis =>
        }
        res <- taskSuccess.attempt
        _ = res should matchPattern {
          case Left(_: CircuitBreaker.ExecutionRejectedException) =>
        }
        _ <- IO.sleep(1.nano) // This timeout is intentionally small b/c actuall time is not deterministic
        // Should still fail-fast
        res2 <- taskSuccess.attempt
        _ = res2 should matchPattern {
          case Left(_: CircuitBreaker.ExecutionRejectedException) =>
        }
        _ <- IO.sleep(200.millis)

        // Testing half-open state
        d <- Deferred[IO, Unit]
        fiber <- circuitBreaker.protect(d.get).start
        _ <- IO.sleep(10.millis)
        _ = circuitBreaker.unsafeState() should matchPattern {
          case CircuitBreaker.HalfOpen(_) =>
        }

        // Should reject other tasks

        res3 <- taskSuccess.attempt
        _ = res3 should matchPattern {
          case Left(_: CircuitBreaker.ExecutionRejectedException) =>
        }

        _ <- d.complete(())
        _ <- fiber.join

        // Should re-open on success
        _ = circuitBreaker.unsafeState() shouldBe CircuitBreaker.Closed(0)

        _ = (openedCount, closedCount, halfOpenCount, rejectedCount) shouldBe ((1, 1, 1, 3))
      } yield Succeeded

    fa.unsafeToFuture()
  }

  test("validate parameters") {
    intercept[IllegalArgumentException] {
      // Positive maxFailures
      CircuitBreaker[IO].of(
        maxFailures = -1,
        resetTimeout = 1.minute
      ).unsafeRunSync()
    }

    intercept[IllegalArgumentException] {
      // Strictly positive resetTimeout
      CircuitBreaker[IO].of(
        maxFailures = 2,
        resetTimeout = -1.minute
      ).unsafeRunSync()
    }

    intercept[IllegalArgumentException] {
      // exponentialBackoffFactor >= 1
      CircuitBreaker[IO].of(
        maxFailures = 2,
        resetTimeout = 1.minute,
        exponentialBackoffFactor = 0.5
      ).unsafeRunSync()
    }

    intercept[IllegalArgumentException] {
      // Strictly positive maxResetTimeout
      CircuitBreaker[IO].of(
        maxFailures = 2,
        resetTimeout = 1.minute,
        exponentialBackoffFactor = 2,
        maxResetTimeout = Duration.Zero
      ).unsafeRunSync()
    }

    Future(Succeeded)
  }
}
