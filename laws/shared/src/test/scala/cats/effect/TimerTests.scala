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

package cats.effect

import cats.data.EitherT
import org.scalatest.{AsyncFunSuite, Matchers}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class TimerTests extends AsyncFunSuite with Matchers {
  implicit override def executionContext =
    ExecutionContext.global

  type EitherIO[A] = EitherT[IO, Throwable, A]



  test("Timer[IO].clock.realTime") {
    val time = System.currentTimeMillis()
    val io = Timer[IO].clock.realTime(MILLISECONDS)

    for (t2 <- io.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2
    }
  }

  test("Timer[IO].clock.monotonic") {
    val time = System.nanoTime()
    val io = Timer[IO].clock.monotonic(NANOSECONDS)

    for (t2 <- io.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2
    }
  }

  test("Timer[IO].sleep(10.ms)") {
    implicit val timer = Timer[IO]
    val io = for {
      start <- timer.clock.monotonic(MILLISECONDS)
      _ <- timer.sleep(10.millis)
      end <- timer.clock.monotonic(MILLISECONDS)
    } yield {
      end - start
    }

    for (r <- io.unsafeToFuture()) yield {
      r should be >= 9L
    }
  }

  test("Timer[IO].sleep(negative)") {
    val io = Timer[IO].sleep(-10.seconds).map(_ => 10)

    for (r <- io.unsafeToFuture()) yield {
      r shouldBe 10
    }
  }


  test("Timer[EitherT].clock.realTime") {
    val time = System.currentTimeMillis()
    val io = Timer.deriveIO[EitherIO].clock.realTime(MILLISECONDS)

    for (t2 <- io.value.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2.right.getOrElse(0L)
    }
  }

  test("Timer[EitherT].clock.monotonic") {
    val time = System.nanoTime()
    val io = Timer.deriveIO[EitherIO].clock.monotonic(NANOSECONDS)

    for (t2 <- io.value.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2.right.getOrElse(0L)
    }
  }

  test("Timer[EitherT].sleep(10.ms)") {
    implicit val timer = Timer.deriveIO[EitherIO]
    val io = for {
      start <- timer.clock.monotonic(MILLISECONDS)
      _ <- timer.sleep(10.millis)
      end <- timer.clock.monotonic(MILLISECONDS)
    } yield {
      end - start
    }

    for (r <- io.value.unsafeToFuture()) yield {
      r.right.getOrElse(0L) should be > 0L
    }
  }

  test("Timer[EitherT].sleep(negative)") {
    val io = Timer.deriveIO[EitherIO].sleep(-10.seconds).map(_ => 10)

    for (r <- io.value.unsafeToFuture()) yield {
      r.right.getOrElse(0) shouldBe 10
    }
  }
}
