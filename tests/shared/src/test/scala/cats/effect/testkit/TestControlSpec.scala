/*
 * Copyright 2020-2024 Typelevel
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

import org.specs2.matcher.Matcher

import scala.concurrent.CancellationException
import scala.concurrent.duration._

class TestControlSpec extends BaseSpec {

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

  "execute" should {
    "run a simple IO" in real {
      TestControl.execute(simple) flatMap { control =>
        for {
          r1 <- control.results
          _ <- IO(r1 must beNone)

          _ <- control.tick

          r2 <- control.results
          _ <- IO(r2 must beSome(beSucceeded(())))
        } yield ok
      }
    }

    "run a ceded IO in a single tick" in real {
      TestControl.execute(simple) flatMap { control =>
        for {
          r1 <- control.results
          _ <- IO(r1 must beNone)

          _ <- control.tick

          r2 <- control.results
          _ <- IO(r2 must beSome(beSucceeded(())))
        } yield ok
      }
    }

    "run an IO with long sleeps" in real {
      TestControl.execute(longSleeps) flatMap { control =>
        for {
          r1 <- control.results
          _ <- IO(r1 must beNone)

          _ <- control.tick
          r2 <- control.results
          _ <- IO(r2 must beNone)

          int1 <- control.nextInterval
          _ <- IO(int1 mustEqual 1.hour)

          _ <- control.advanceAndTick(1.hour)
          r3 <- control.results
          _ <- IO(r3 must beNone)

          int2 <- control.nextInterval
          _ <- IO(int2 mustEqual 1.day)

          _ <- control.advanceAndTick(1.day)

          r4 <- control.results
          _ <- IO(r4 must beSome(beSucceeded((0.nanoseconds, 1.hour, 25.hours))))
        } yield ok
      }
    }

    "detect a deadlock" in real {
      TestControl.execute(deadlock) flatMap { control =>
        for {
          r1 <- control.results
          _ <- IO(r1 must beNone)

          _ <- control.tick
          id <- control.isDeadlocked
          _ <- IO(id must beTrue)

          r2 <- control.results
          _ <- IO(r2 must beNone)
        } yield ok
      }
    }

    "only detect a deadlock when results are unavailable" in real {
      TestControl.execute(IO.unit) flatMap { control =>
        for {
          r1 <- control.results
          _ <- IO(r1 must beNone)

          _ <- control.tick
          id <- control.isDeadlocked
          _ <- IO(id must beFalse)

          r2 <- control.results
          _ <- IO(r2 must beSome)
        } yield ok
      }
    }

    "produce Duration.Zero from nextInterval when no tasks" in real {
      TestControl.execute(deadlock) flatMap { control =>
        for {
          _ <- control.tick
          i <- control.nextInterval
          _ <- IO(i mustEqual Duration.Zero)
        } yield ok
      }
    }

    "tickFor" >> {
      "not advance beyond limit" in real {
        TestControl.execute(IO.sleep(1.second).as(42)) flatMap { control =>
          for {
            r1 <- control.results
            _ <- IO(r1 must beNone)

            _ <- control.tickFor(500.millis)
            r2 <- control.results
            _ <- IO(r2 must beNone)

            i1 <- control.nextInterval
            _ <- IO(i1 mustEqual 500.millis)

            _ <- control.tickFor(250.millis)
            r3 <- control.results
            _ <- IO(r3 must beNone)

            i2 <- control.nextInterval
            _ <- IO(i2 mustEqual 250.millis)

            _ <- control.tickFor(250.millis)
            r4 <- control.results
            _ <- IO(r4 must beSome(beSucceeded(42)))
          } yield ok
        }
      }

      "advance incrementally in minimum steps" in real {
        val step = IO.sleep(1.second) *> IO.realTime

        TestControl.execute((step, step).tupled) flatMap { control =>
          for {
            _ <- control.tickFor(1.second + 500.millis)
            _ <- control.tickAll

            r <- control.results
            _ <- IO(r must beSome(beSucceeded((1.second, 2.seconds))))
          } yield ok
        }
      }
    }
  }

  "executeEmbed" should {
    "run a simple IO" in real {
      TestControl.executeEmbed(simple) flatMap { r => IO(r mustEqual (())) }
    }

    "run an IO with long sleeps" in real {
      TestControl.executeEmbed(longSleeps) flatMap { r =>
        IO(r mustEqual ((0.nanoseconds, 1.hour, 25.hours)))
      }
    }

    "detect a deadlock" in real {
      TestControl.executeEmbed(deadlock).attempt flatMap { r =>
        IO {
          r must beLike { case Left(_: TestControl.NonTerminationException) => ok }
        }
      }
    }

    "run an IO which produces an error" in real {
      case object TestException extends RuntimeException

      TestControl.executeEmbed(IO.raiseError[Unit](TestException)).attempt flatMap { r =>
        IO(r must beLeft(TestException: Throwable))
      }
    }

    "run an IO which self-cancels" in real {
      TestControl.executeEmbed(IO.canceled).attempt flatMap { r =>
        IO {
          r must beLike { case Left(_: CancellationException) => ok }
        }
      }
    }
  }

  private def beSucceeded[A](value: A): Matcher[Outcome[Id, Throwable, A]] =
    (_: Outcome[Id, Throwable, A]) == Outcome.succeeded[Id, Throwable, A](value)
}
