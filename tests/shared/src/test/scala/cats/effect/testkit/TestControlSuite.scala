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
package testkit

import cats.Id
import cats.syntax.all._

import scala.concurrent.CancellationException
import scala.concurrent.duration._

class TestControlSuite extends BaseSuite {

  val simple = IO.unit

  val ceded = IO.cede.replicateA(10) *> IO.unit

  val longSleeps = for {
    first <- IO.monotonic
    _ <- IO.sleep(1.hour)
    second <- IO.monotonic
    _ <- IO.race(IO.sleep(1.day), IO.sleep(1.day + 1.nanosecond))
    third <- IO.monotonic
  } yield (first.toCoarsest, second.toCoarsest, third.toCoarsest)

  val deadlock: IO[Unit] = IO.never

  real("execute - run a simple IO") {
    TestControl.execute(simple) flatMap { control =>
      for {
        r1 <- control.results
        _ <- IO(assertEquals(r1, None))

        _ <- control.tick

        r2 <- control.results
        _ <- IO(assertEquals(r2, Some(beSucceeded(()))))
      } yield ()
    }
  }

  real("execute - run a ceded IO in a single tick") {
    TestControl.execute(simple) flatMap { control =>
      for {
        r1 <- control.results
        _ <- IO(assertEquals(r1, None))

        _ <- control.tick

        r2 <- control.results
        _ <- IO(assertEquals(r2, Some(beSucceeded(()))))
      } yield ()
    }
  }

  real("execute - run an IO with long sleeps") {
    TestControl.execute(longSleeps) flatMap { control =>
      for {
        r1 <- control.results
        _ <- IO(assertEquals(r1, None))

        _ <- control.tick
        r2 <- control.results
        _ <- IO(assertEquals(r2, None))

        int1 <- control.nextInterval
        _ <- IO(assertEquals(int1, 1.hour))

        _ <- control.advanceAndTick(1.hour)
        r3 <- control.results
        _ <- IO(assertEquals(r3, None))

        int2 <- control.nextInterval
        _ <- IO(assertEquals(int2, 1.day))

        _ <- control.advanceAndTick(1.day)

        r4 <- control.results
        _ <- IO(assertEquals(r4, Some(beSucceeded((0.nanoseconds, 1.hour, 25.hours)))))
      } yield ()
    }
  }

  real("execute - detect a deadlock") {
    TestControl.execute(deadlock) flatMap { control =>
      for {
        r1 <- control.results
        _ <- IO(assertEquals(r1, None))

        _ <- control.tick
        id <- control.isDeadlocked
        _ <- IO(assert(id))

        r2 <- control.results
        _ <- IO(assertEquals(r2, None))
      } yield ()
    }
  }

  real("execute - only detect a deadlock when results are unavailable") {
    TestControl.execute(IO.unit) flatMap { control =>
      for {
        r1 <- control.results
        _ <- IO(assertEquals(r1, None))

        _ <- control.tick
        id <- control.isDeadlocked
        _ <- IO(assert(!id))

        r2 <- control.results
        _ <- IO(assert(r2.isDefined))
      } yield ()
    }
  }

  real("execute - produce Duration.Zero from nextInterval when no tasks") {
    TestControl.execute(deadlock) flatMap { control =>
      for {
        _ <- control.tick
        i <- control.nextInterval
        _ <- IO(assertEquals(i, Duration.Zero))
      } yield ()
    }
  }

  real("execute - tickFor - not advance beyond limit") {
    TestControl.execute(IO.sleep(1.second).as(42)) flatMap { control =>
      for {
        r1 <- control.results
        _ <- IO(assertEquals(r1, None))

        _ <- control.tickFor(500.millis)
        r2 <- control.results
        _ <- IO(assertEquals(r2, None))

        i1 <- control.nextInterval
        _ <- IO(assertEquals(i1, 500.millis))

        _ <- control.tickFor(250.millis)
        r3 <- control.results
        _ <- IO(assertEquals(r3, None))

        i2 <- control.nextInterval
        _ <- IO(assertEquals(i2, 250.millis))

        _ <- control.tickFor(250.millis)
        r4 <- control.results
        _ <- IO(assertEquals(r4, Some(beSucceeded(42))))
      } yield ()
    }
  }

  real("execute - tickFor - advance incrementally in minimum steps") {
    val step = IO.sleep(1.second) *> IO.realTime

    TestControl.execute((step, step).tupled) flatMap { control =>
      for {
        _ <- control.tickFor(1.second + 500.millis)
        _ <- control.tickAll

        r <- control.results
        _ <- IO(assertEquals(r, Some(beSucceeded((1.second, 2.seconds)))))
      } yield ()
    }
  }

  real("executeEmbed - run a simple IO") {
    TestControl.executeEmbed(simple) flatMap { r => IO(assertEquals(r, ())) }
  }

  real("executeEmbed - run an IO with long sleeps") {
    TestControl.executeEmbed(longSleeps) flatMap { r =>
      IO(assertEquals(r, (0.nanoseconds, 1.hour, 25.hours)))
    }
  }

  real("executeEmbed - detect a deadlock") {
    TestControl.executeEmbed(deadlock).attempt flatMap { r =>
      IO {
        r match {
          case Left(e) => assert(e.isInstanceOf[TestControl.NonTerminationException])
          case Right(v) => fail(s"Expected Left, got $v")
        }
      }
    }
  }

  real("executeEmbed - run an IO which produces an error") {
    case object TestException extends RuntimeException

    TestControl.executeEmbed(IO.raiseError[Unit](TestException)).attempt flatMap { r =>
      IO {
        r match {
          case Left(e) => assertEquals(e, TestException)
          case Right(v) => fail(s"Expected Left, got $v")
        }
      }
    }
  }

  real("executeEmbed - run an IO which self-cancels") {
    TestControl.executeEmbed(IO.canceled).attempt flatMap { r =>
      IO {
        r match {
          case Left(e) => assert(e.isInstanceOf[CancellationException])
          case Right(v) => fail(s"Expected Left, got $v")
        }
      }
    }
  }

  private def beSucceeded[A](value: A): Outcome[Id, Throwable, A] =
    Outcome.succeeded[Id, Throwable, A](value)
}
