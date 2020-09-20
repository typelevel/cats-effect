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

package cats
package effect

import org.specs2.specification.core.Execution
import org.specs2.execute._

import cats.implicits._
import scala.concurrent.duration._

class ContSpec extends BaseSpec { outer =>

  // TODO can use real after debugging
  def realNoTimeout[A: AsResult](test: => IO[A]): Execution =
    Execution.withEnvAsync(_ => test.unsafeToFuture()(runtime()))

  def execute(io: IO[_], times: Int, i: Int = 0): IO[Success] =
    if (i == times) IO(success)
    else io >> execute(io, times, i + 1)


  // TODO move these to IOSpec. Generally review our use of `ticked` in IOSpec
  // various classcast exceptions and/or ByteStack going out of bound
  "get resumes" in realNoTimeout {
    val io =
      IO.cont[Int].flatMap { case (get, resume) =>
        IO(resume(Right(42))) >> get
      }

    val test = io.flatMap(r => IO(r mustEqual 42))

    execute(test, 100000)
  }

  "callback resumes" in realNoTimeout {
    val (scheduler, close) = unsafe.IORuntime.createDefaultScheduler()

    val io =
      IO.cont[Int] flatMap { case (get, resume) =>
        IO(scheduler.sleep(10.millis, () => resume(Right(42)))) >> get
      }

    val test = io.flatMap(r => IO(r mustEqual 42))

    execute(test, 100).guarantee(IO(close))
  }

  val iterations = 100000

  "async" should {

    "resume value continuation within async" in realNoTimeout {
      val io =
        IO.async[Int](k => IO(k(Right(42))).map(_ => None))

      val test = io.flatMap(r => IO(r mustEqual 42))

      execute(test, iterations)
    }

    "continue from the results of an async produced prior to registration" in realNoTimeout {
      val io =
        IO.async[Int](cb => IO(cb(Right(42))).as(None)).map(_ + 2)

      val test = io.flatMap(r => IO(r mustEqual 44))

      execute(test, iterations)
    }

    //   // format: off
    //   "produce a failure when the registration raises an error after callback" in ticked { implicit ticker =>
    //     case object TestException extends RuntimeException

    //     IO.async[Int](cb => IO(cb(Right(42)))
    //       .flatMap(_ => IO.raiseError(TestException)))
    //       .void must failAs(TestException)
    //   }
    // // format: on

    //   "repeated async callback" in ticked { implicit ticker =>
    //     case object TestException extends RuntimeException

    //     var cb: Either[Throwable, Int] => Unit = null

    //     val async = IO.async_[Int] { cb0 => cb = cb0 }

    //     val test = for {
    //       fiber <- async.start
    //       _ <- IO(ticker.ctx.tickAll())
    //       _ <- IO(cb(Right(42)))
    //       _ <- IO(ticker.ctx.tickAll())
    //       _ <- IO(cb(Right(43)))
    //       _ <- IO(ticker.ctx.tickAll())
    //       _ <- IO(cb(Left(TestException)))
    //       _ <- IO(ticker.ctx.tickAll())
    //       value <- fiber.joinAndEmbedNever
    //     } yield value

    //     test must completeAs(42)
    //   }

      "complete a fiber with Canceled under finalizer on poll" in realNoTimeout {
        val io =
          IO.uncancelable(p => IO.canceled >> p(IO.unit).guarantee(IO.unit))
            .start
            .flatMap(_.join)

        val test = io.flatMap(r => IO(r mustEqual Outcome.canceled[IO, Throwable, Unit]))

        execute(test, iterations)
      }

      "invoke multiple joins on fiber completion" in realNoTimeout {
        val io = for {
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

        val test = io.flatMap { results =>
          results.traverse { result =>
            IO(result must beLike { case Outcome.Completed(_) => ok }).flatMap { _ =>
              result match {
                case Outcome.Completed(ioa) =>
                  ioa.flatMap { oc =>
                    IO(result must beLike { case Outcome.Completed(_) => ok }).flatMap { _ =>
                      oc match {
                        case Outcome.Completed(ioa) =>
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

        execute(test, iterations)
      }

      "race" should {
        "evaluate a timeout using sleep and race in real time" in realNoTimeout {
          val io = IO.race(IO.never[Unit], IO.sleep(10.millis))
          val test = io.flatMap { res => IO(res must beRight(())) }

          execute(test, 100)
        }
      }

    "return the left when racing against never" in realNoTimeout {
      val io = IO.pure(42)
        .racePair(IO.never: IO[Unit])
        .map(_.left.toOption.map(_._1).get)

      val test = io.flatMap { r => IO(r mustEqual Outcome.completed[IO, Throwable, Int](IO.pure(42)) )}
      execute(test, iterations)
    }

  }

}

