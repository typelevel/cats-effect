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
package concurrent

import cats.syntax.all._

import scala.concurrent.duration._

import org.specs2.specification.core.Execution
import org.specs2.execute._

class DeferredSpec extends BaseSpec { outer =>

  sequential

  def execute(io: IO[_], times: Int, i: Int = 0): IO[Success] =
    if (i == times) IO(success)
    else io >> execute(io, times, i + 1)

  val iterations = 100000

  def realNoTimeout[A: AsResult](test: => IO[A]): Execution =
    Execution.withEnvAsync(_ => test.unsafeToFuture()(runtime()))


  "Deferred" >> {

    "complete" in realNoTimeout {
      val op = Deferred[IO, Int].flatMap { p => p.complete(0) *> p.get }

      val test = op.flatMap { res =>
        IO {
          res must beEqualTo(0)
        }
      }

      execute(test, iterations)
    }

    "complete is only successful once" in realNoTimeout {
      val op = Deferred[IO, Int].flatMap { p => p.complete(0) *> p.complete(1).product(p.get) }

      val test = op.flatMap { res =>
        IO {
          res must beEqualTo((false, 0))
        }
      }

      execute(test, iterations)
    }

    "get blocks until set" in realNoTimeout {
      val op = for {
        state <- Ref[IO].of(0)
        modifyGate <- Deferred[IO, Unit]
        readGate <- Deferred[IO, Unit]
        _ <- (modifyGate.get *> state.update(_ * 2) *> readGate.complete(())).start
        _ <- (state.set(1) *> modifyGate.complete(())).start
        _ <- readGate.get
        res <- state.get
      } yield res

      val test = op.flatMap { res =>
        IO {
          res must beEqualTo(2)
        }
      }

      execute(test, iterations)
    }

    "concurrent - get - cancel before forcing" in realNoTimeout {
      def cancelBeforeForcing: IO[Option[Int]] =
        for {
          r <- Ref[IO].of(Option.empty[Int])
          p <- Deferred[IO, Int]
          fiber <- p.get.start
          _ <- fiber.cancel
          _ <- (fiber
              .join
              .flatMap {
                case Outcome.Completed(ioi) => ioi.flatMap(i => r.set(Some(i)))
                case _ => IO.raiseError(new RuntimeException)
              })
            .start
          _ <- IO.sleep(10.millis)
          _ <- p.complete(42)
          _ <- IO.sleep(10.millis)
          result <- r.get
        } yield result

      val test = cancelBeforeForcing.flatMap { res =>
        IO {
          res must beNone
        }
      }

      execute(test, 100)
    }

    "tryGet returns None for unset Deferred" in realNoTimeout {
      val op = Deferred[IO, Unit].flatMap(_.tryGet)

      val test = op.flatMap { res =>
        IO {
          res must beNone
        }
      }

      execute(test, iterations)
    }

    "tryGet returns Some() for set Deferred" in realNoTimeout {
      val op = for {
        d <- Deferred[IO, Unit]
        _ <- d.complete(())
        result <- d.tryGet
      } yield result

      val test = op.flatMap { res =>
        IO {
          res must beEqualTo(Some(()))
        }
      }

      execute(test, iterations)
    }

    "issue #380: complete doesn't block, test #1" in real {
      def execute(times: Int): IO[Boolean] = {
        def foreverAsync(i: Int): IO[Unit] =
          if (i == 512) IO.async[Unit] { cb =>
            cb(Right(()))
            IO.pure(None)
          } >> foreverAsync(0)
          else IO.unit >> foreverAsync(i + 1)

        val task = for {
          d <- Deferred[IO, Unit]
          latch <- Deferred[IO, Unit]
          fb <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
          _ <- latch.get
          _ <- d.complete(()).timeout(15.seconds).guarantee(fb.cancel)
        } yield {
          true
        }

        task.flatMap { r =>
          if (times > 0) execute(times - 1)
          else IO.pure(r)
        }
      }

      execute(100).flatMap { res =>
        IO {
          res must beTrue
        }
      }
    }

  }
}
