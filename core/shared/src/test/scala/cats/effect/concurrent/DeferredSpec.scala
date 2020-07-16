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

import cats.implicits._

import org.specs2.specification.core.Fragments

import scala.concurrent.duration._

class DeferredSpec extends BaseSpec { outer =>

  sequential

  trait DeferredConstructor { def apply[A]: IO[Deferred[IO, A]] }
  trait TryableDeferredConstructor { def apply[A]: IO[TryableDeferred[IO, A]] }

  "deferred" should {

    tests("concurrent", new DeferredConstructor { def apply[A] = Deferred[IO, A] })
    tests("concurrentTryable", new DeferredConstructor { def apply[A] = Deferred.tryable[IO, A] })

    tryableTests("concurrentTryable", new TryableDeferredConstructor { def apply[A] = Deferred.tryable[IO, A] })

    "concurrent - get - cancel before forcing" in real {
      cancelBeforeForcing(Deferred.apply).flatMap { res =>
        IO {
          res must beNone
        }
      }
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

  def tests(label: String, pc: DeferredConstructor): Fragments = {
    s"$label - complete" in real {
      val op = pc[Int]
        .flatMap { p =>
          p.complete(0) *> p.get
        }

      op.flatMap { res =>
        IO {
          res must beEqualTo(0)
        }
      }
    }

    s"$label - complete is only successful once" in real {
      val op = pc[Int]
        .flatMap { p =>
          (p.complete(0) *> p.complete(1).attempt).product(p.get)
        }

      op.flatMap { res =>
        IO {
          res must beLike {
            case (Left(e), 0) => e must haveClass[IllegalStateException]
          }
        }
      }
    }

    s"$label - get blocks until set" in real {
      val op = for {
        state <- Ref[IO].of(0)
        modifyGate <- pc[Unit]
        readGate <- pc[Unit]
        _ <- (modifyGate.get *> state.update(_ * 2) *> readGate.complete(())).start
        _ <- (state.set(1) *> modifyGate.complete(())).start
        _ <- readGate.get
        res <- state.get
      } yield res

      op.flatMap { res =>
        IO {
          res must beEqualTo(2)
        }
      }
    }
  }

  def tryableTests(label: String, pc: TryableDeferredConstructor): Fragments = {
    s"$label - tryGet returns None for unset Deferred" in real {
      val op = pc[Unit].flatMap(_.tryGet)

      op.flatMap { res =>
        IO {
          res must beNone
        }
      }
    }

    s"$label - tryGet returns Some() for set Deferred" in real {
      val op = for {
        d <- pc[Unit]
        _ <- d.complete(())
        result <- d.tryGet
      } yield result

      op.flatMap { res =>
        IO {
          res must beEqualTo(Some(()))
        }
      }
    }
  }

  private def cancelBeforeForcing(pc: IO[Deferred[IO, Int]]): IO[Option[Int]] =
    for {
      r <- Ref[IO].of(Option.empty[Int])
      p <- pc
      fiber <- p.get.start
      _ <- fiber.cancel
      _ <- (fiber.join.flatMap {
        case Outcome.Completed(ioi) => ioi.flatMap(i => r.set(Some(i)))
        case _                      => IO.raiseError(new RuntimeException)
      }).start
      _ <- IO.sleep(100.millis)
      _ <- p.complete(42)
      _ <- IO.sleep(100.millis)
      result <- r.get
    } yield result

}
