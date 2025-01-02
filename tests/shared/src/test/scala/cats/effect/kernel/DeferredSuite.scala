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

package cats
package effect
package kernel

import cats.syntax.all._

import scala.concurrent.duration._

class DeferredSuite extends BaseSuite with DetectPlatform { outer =>

  tests("Deferred for Async", IO(Deferred.unsafe), IO(Deferred.unsafe))

  tests("Deferred for IO", IO(new IODeferred), IO(new IODeferred))

  def tests(
      name: String,
      deferredU: IO[Deferred[IO, Unit]],
      deferredI: IO[Deferred[IO, Int]]) = {
    real(s"$name complete") {
      val op = deferredI.flatMap { p => p.complete(0) *> p.get }

      op.flatMap { res =>
        IO {
          assertEquals(res, 0)
        }
      }
    }

    real(s"$name complete is only successful once") {
      val op = deferredI.flatMap { p => p.complete(0) *> p.complete(1).product(p.get) }

      op.flatMap { res =>
        IO {
          assertEquals(res, (false, 0))
        }
      }
    }

    real(s"$name get blocks until set") {
      val op = for {
        state <- Ref[IO].of(0)
        modifyGate <- deferredU
        readGate <- deferredU
        _ <- (modifyGate.get *> state.update(_ * 2) *> readGate.complete(())).start
        _ <- (state.set(1) *> modifyGate.complete(())).start
        _ <- readGate.get
        res <- state.get
      } yield res

      op.flatMap { res =>
        IO {
          assertEquals(res, 2)
        }
      }
    }

    real(s"$name concurrent - get - cancel before forcing") {
      def cancelBeforeForcing: IO[Option[Int]] =
        for {
          r <- Ref[IO].of(Option.empty[Int])
          p <- Deferred[IO, Int]
          fiber <- p.get.start
          _ <- fiber.cancel
          _ <- fiber
            .join
            .flatMap {
              case Outcome.Succeeded(ioi) => ioi.flatMap(i => r.set(Some(i)))
              case _ => IO.raiseError(new RuntimeException)
            }
            .voidError
            .start
          _ <- IO.sleep(100.millis)
          _ <- p.complete(42)
          _ <- IO.sleep(100.millis)
          result <- r.get
        } yield result

      cancelBeforeForcing.flatMap { res =>
        IO {
          assertEquals(res, None)
        }
      }
    }

    real(s"$name tryGet returns None for unset Deferred") {
      val op = deferredU.flatMap(_.tryGet)

      op.flatMap { res =>
        IO {
          assertEquals(res, None)
        }
      }
    }

    real(s"$name tryGet returns Some() for set Deferred") {
      val op = for {
        d <- deferredU
        _ <- d.complete(())
        result <- d.tryGet
      } yield result

      op.flatMap { res =>
        IO {
          assertEquals(res, Some(()))
        }
      }
    }

    real(s"$name issue #380: complete doesn't block, test #1") {
      def execute(times: Int): IO[Boolean] = {
        def foreverAsync(i: Int): IO[Unit] =
          if (i == 512) IO.async[Unit] { cb =>
            cb(Right(()))
            IO.pure(None)
          } >> foreverAsync(0)
          else IO.unit >> foreverAsync(i + 1)

        val task = for {
          d <- deferredU
          latch <- deferredU
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
          assert(res)
        }
      }
    }

    real(s"$name issue #3283: complete must be uncancelable") {

      import cats.syntax.all._

      for {
        d <- deferredI
        attemptCompletion = { (n: Int) => d.complete(n).void }
        res <- List(
          IO.race(attemptCompletion(1), attemptCompletion(2)).void,
          d.get.void
        ).parSequence
      } yield assertEquals(res, List((), ()))
    }

    real(s"$name handle lots of canceled gets in parallel") {
      List(10, 100, 1000).traverse_ { n =>
        deferredU
          .flatMap { d =>
            (d.get.background.surround(IO.cede).replicateA_(n) *> d
              .complete(())).background.surround {
              d.get.as(1).parReplicateA(n).map(r => assertEquals(r.sum, n))
            }
          }
          .replicateA_(if (isJVM) 100 else 1)
      }
    }

    ticked("handle adversarial cancelations without loss of callbacks") { implicit ticker =>
      val test = for {
        d <- deferredU

        range = 0.until(512)
        fibers <- range.toVector.traverse(_ => d.get.start <* IO.sleep(1.millis))

        // these are mostly randomly chosen
        // the consecutive runs are significant, but only loosely so
        // the point is to trigger packing but ensure it isn't always successful
        toCancel = List(12, 23, 201, 405, 1, 7, 17, 27, 127, 203, 204, 207, 2, 3, 4, 5)
        _ <- toCancel.traverse_(fibers(_).cancel)

        _ <- d.complete(())
        remaining = range.toSet -- toCancel

        // this will deadlock if any callbacks are lost
        _ <- remaining.toList.traverse_(fibers(_).join.void)
      } yield ()

      assertCompleteAs(test, ())
    }
  }
}
