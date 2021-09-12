/*
 * Copyright 2020-2021 Typelevel
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

import org.specs2.matcher.Matcher

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
  }

  "executeFully" should {
    "run a simple IO" in real {
      TestControl.executeFully(simple) flatMap { r => IO(r must beSome(beSucceeded(()))) }
    }

    "run an IO with long sleeps" in real {
      TestControl.executeFully(longSleeps) flatMap { r =>
        IO(r must beSome(beSucceeded((0.nanoseconds, 1.hour, 25.hours))))
      }
    }

    "detect a deadlock" in real {
      TestControl.executeFully(deadlock) flatMap { r => IO(r must beNone) }
    }

    "run an IO which produces an error" in real {
      case object TestException extends RuntimeException

      TestControl.executeFully(IO.raiseError[Unit](TestException)) flatMap { r =>
        IO(r must beSome(beErrored[Unit](TestException)))
      }
    }

    "run an IO which self-cancels" in real {
      TestControl.executeFully(IO.canceled) flatMap { r => IO(r must beSome(beCanceled[Unit])) }
    }
  }

  private def beSucceeded[A](value: A): Matcher[Outcome[Id, Throwable, A]] =
    (_: Outcome[Id, Throwable, A]) == Outcome.succeeded[Id, Throwable, A](value)

  private def beErrored[A](t: Throwable): Matcher[Outcome[Id, Throwable, A]] =
    (_: Outcome[Id, Throwable, A]) == Outcome.errored[Id, Throwable, A](t)

  private def beCanceled[A]: Matcher[Outcome[Id, Throwable, A]] =
    (_: Outcome[Id, Throwable, A]) == Outcome.canceled[Id, Throwable, A]
}
