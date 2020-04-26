/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

import cats.implicits._
import cats.data._
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AsyncFunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class TimerTests extends AsyncFunSuite with Matchers {
  implicit override def executionContext =
    ExecutionContext.global
  implicit val timer: Timer[IO] =
    IO.timer(executionContext)

  type EitherTIO[A] = EitherT[IO, Throwable, A]
  type OptionTIO[A] = OptionT[IO, A]
  type WriterTIO[A] = WriterT[IO, Int, A]
  type KleisliIO[A] = Kleisli[IO, Int, A]
  type StateTIO[A] = StateT[IO, Int, A]
  type IorTIO[A] = IorT[IO, Int, A]
  type ResourceIO[A] = Resource[IO, A]

  def resolveClock = {
    Clock[EitherTIO]
    Clock[OptionTIO]
    Clock[WriterTIO]
    Clock[KleisliIO]
    Clock[StateTIO]
    Clock[IorTIO]
    Clock[ResourceIO]
  }

  test("Timer[IO].clock.realTime") {
    val time = System.currentTimeMillis()
    val io = timer.clock.realTime(MILLISECONDS)

    for (t2 <- io.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2
    }
  }

  test("Timer[IO].clock.monotonic") {
    val time = System.nanoTime()
    val io = timer.clock.monotonic(NANOSECONDS)

    for (t2 <- io.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2
    }
  }

  test("Timer[IO].sleep(100.ms)") {
    val io = for {
      start <- timer.clock.monotonic(MILLISECONDS)
      _ <- timer.sleep(100.millis)
      end <- timer.clock.monotonic(MILLISECONDS)
    } yield {
      end - start
    }

    for (r <- io.unsafeToFuture()) yield {
      r should be >= 90L
    }
  }

  test("Timer[IO].sleep(negative)") {
    val io = timer.sleep(-10.seconds).map(_ => 10)

    for (r <- io.unsafeToFuture()) yield {
      r shouldBe 10
    }
  }

  test("Timer[EitherT].clock.realTime") {
    val time = System.currentTimeMillis()
    val io = Timer[EitherTIO].clock.realTime(MILLISECONDS)

    for (t2 <- io.value.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2.getOrElse(0L)
    }
  }

  test("Timer[EitherT].clock.monotonic") {
    val time = System.nanoTime()
    val io = Timer[EitherTIO].clock.monotonic(NANOSECONDS)

    for (t2 <- io.value.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2.getOrElse(0L)
    }
  }

  test("Timer[EitherT].sleep(100.ms)") {
    val t = Timer[EitherTIO]
    val io = for {
      start <- t.clock.monotonic(MILLISECONDS)
      _ <- t.sleep(100.millis)
      end <- t.clock.monotonic(MILLISECONDS)
    } yield {
      end - start
    }

    for (r <- io.value.unsafeToFuture()) yield {
      r.getOrElse(0L) should be > 0L
    }
  }

  test("Timer[EitherT].sleep(negative)") {
    val io = Timer[EitherTIO].sleep(-10.seconds).map(_ => 10)

    for (r <- io.value.unsafeToFuture()) yield {
      r.getOrElse(0) shouldBe 10
    }
  }

  // --- OptionT

  test("Timer[OptionT].clock.realTime") {
    val time = System.currentTimeMillis()
    val io = Timer[OptionTIO].clock.realTime(MILLISECONDS)

    for (t2 <- io.value.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2.getOrElse(0L)
    }
  }

  test("Timer[OptionT].clock.monotonic") {
    val time = System.nanoTime()
    val io = Timer[OptionTIO].clock.monotonic(NANOSECONDS)

    for (t2 <- io.value.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2.getOrElse(0L)
    }
  }

  test("Timer[OptionT].sleep(100.ms)") {
    val t = Timer[OptionTIO]
    val io = for {
      start <- t.clock.monotonic(MILLISECONDS)
      _ <- t.sleep(100.millis)
      end <- t.clock.monotonic(MILLISECONDS)
    } yield {
      end - start
    }

    for (r <- io.value.unsafeToFuture()) yield {
      r.getOrElse(0L) should be > 0L
    }
  }

  test("Timer[OptionT].sleep(negative)") {
    val io = Timer[OptionTIO].sleep(-10.seconds).map(_ => 10)

    for (r <- io.value.unsafeToFuture()) yield {
      r.getOrElse(0) shouldBe 10
    }
  }

  // --- WriterT

  test("Timer[WriterT].clock.realTime") {
    val time = System.currentTimeMillis()
    val io = Timer[WriterTIO].clock.realTime(MILLISECONDS)

    for (t2 <- io.value.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2
    }
  }

  test("Timer[WriterT].clock.monotonic") {
    val time = System.nanoTime()
    val io = Timer[WriterTIO].clock.monotonic(NANOSECONDS)

    for (t2 <- io.value.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2
    }
  }

  test("Timer[WriterT].sleep(100.ms)") {
    val t = Timer[WriterTIO]
    val io = for {
      start <- t.clock.monotonic(MILLISECONDS)
      _ <- t.sleep(100.millis)
      end <- t.clock.monotonic(MILLISECONDS)
    } yield {
      end - start
    }

    for (r <- io.value.unsafeToFuture()) yield {
      r should be > 0L
    }
  }

  test("Timer[WriterT].sleep(negative)") {
    val io = Timer[WriterTIO].sleep(-10.seconds).map(_ => 10)

    for (r <- io.value.unsafeToFuture()) yield {
      r shouldBe 10
    }
  }

  // --- Kleisli

  test("Timer[Kleisli].clock.realTime") {
    val time = System.currentTimeMillis()
    val io = Timer[KleisliIO].clock.realTime(MILLISECONDS)

    for (t2 <- io.run(0).unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2
    }
  }

  test("Timer[Kleisli].clock.monotonic") {
    val time = System.nanoTime()
    val io = Timer[KleisliIO].clock.monotonic(NANOSECONDS)

    for (t2 <- io.run(0).unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2
    }
  }

  test("Timer[Kleisli].sleep(100.ms)") {
    val t = Timer[KleisliIO]
    val io = for {
      start <- t.clock.monotonic(MILLISECONDS)
      _ <- t.sleep(100.millis)
      end <- t.clock.monotonic(MILLISECONDS)
    } yield {
      end - start
    }

    for (r <- io.run(0).unsafeToFuture()) yield {
      r should be > 0L
    }
  }

  test("Timer[Kleisli].sleep(negative)") {
    val io = Timer[KleisliIO].sleep(-10.seconds).map(_ => 10)

    for (r <- io.run(0).unsafeToFuture()) yield {
      r shouldBe 10
    }
  }

  // --- StateT

  test("Timer[StateT].clock.realTime") {
    val time = System.currentTimeMillis()
    val io = Timer[StateTIO].clock.realTime(MILLISECONDS)

    for (t2 <- io.run(0).unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2._2
    }
  }

  test("Timer[StateT].clock.monotonic") {
    val time = System.nanoTime()
    val io = Timer[StateTIO].clock.monotonic(NANOSECONDS)

    for (t2 <- io.run(0).unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2._2
    }
  }

  test("Timer[StateT].sleep(100.ms)") {
    val t = Timer[StateTIO]
    val io = for {
      start <- t.clock.monotonic(MILLISECONDS)
      _ <- t.sleep(100.millis)
      end <- t.clock.monotonic(MILLISECONDS)
    } yield {
      end - start
    }

    for (r <- io.run(0).unsafeToFuture()) yield {
      r._2 should be > 0L
    }
  }

  test("Timer[StateT].sleep(negative)") {
    val io = Timer[StateTIO].sleep(-10.seconds).map(_ => 10)

    for (r <- io.run(0).unsafeToFuture()) yield {
      r._2 shouldBe 10
    }
  }

  // --- IorT

  test("Timer[IorT].clock.realTime") {
    val time = System.currentTimeMillis()
    val io = Timer[IorTIO].clock.realTime(MILLISECONDS)

    for (t2 <- io.value.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2.getOrElse(0L)
    }
  }

  test("Timer[IorT].clock.monotonic") {
    val time = System.nanoTime()
    val io = Timer[IorTIO].clock.monotonic(NANOSECONDS)

    for (t2 <- io.value.unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2.getOrElse(0L)
    }
  }

  test("Timer[IorT].sleep(100.ms)") {
    val t = Timer[IorTIO]
    val io = for {
      start <- t.clock.monotonic(MILLISECONDS)
      _ <- t.sleep(100.millis)
      end <- t.clock.monotonic(MILLISECONDS)
    } yield {
      end - start
    }

    for (r <- io.value.unsafeToFuture()) yield {
      r.getOrElse(0L) should be > 0L
    }
  }

  test("Timer[IorT].sleep(negative)") {
    val io = Timer[IorTIO].sleep(-10.seconds).map(_ => 10)

    for (r <- io.value.unsafeToFuture()) yield {
      r.getOrElse(0L) shouldBe 10
    }
  }

  // --- Resource

  test("Timer[Resource].clock.realTime") {
    val time = System.currentTimeMillis()
    val io = Timer[ResourceIO].clock.realTime(MILLISECONDS)

    for (t2 <- io.use(IO.pure).unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2
    }
  }

  test("Timer[Resource].clock.monotonic") {
    val time = System.nanoTime()
    val io = Timer[ResourceIO].clock.monotonic(NANOSECONDS)

    for (t2 <- io.use(IO.pure).unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2
    }
  }

  test("Timer[Resource].sleep(100.ms)") {
    val t = Timer[ResourceIO]
    val io = for {
      start <- t.clock.monotonic(MILLISECONDS)
      _ <- t.sleep(100.millis)
      end <- t.clock.monotonic(MILLISECONDS)
    } yield {
      end - start
    }

    for (r <- io.use(IO.pure).unsafeToFuture()) yield {
      r should be > 0L
    }
  }

  test("Timer[Resource].sleep(negative)") {
    val io = Timer[ResourceIO].sleep(-10.seconds).map(_ => 10)

    for (r <- io.use(IO.pure).unsafeToFuture()) yield {
      r shouldBe 10
    }
  }
}
