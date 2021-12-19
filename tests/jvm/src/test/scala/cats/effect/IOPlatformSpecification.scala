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

import cats.effect.std.Semaphore
import cats.syntax.all._

import org.scalacheck.Prop.forAll
import org.specs2.ScalaCheck

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.{
  CancellationException,
  CompletableFuture,
  CountDownLatch,
  Executors
}

trait IOPlatformSpecification { self: BaseSpec with ScalaCheck =>

  def platformSpecs = {
    "platform" should {

      "shift delay evaluation within evalOn" in real {
        val Exec1Name = "testing executor 1"
        val exec1 = Executors.newSingleThreadExecutor { r =>
          val t = new Thread(r)
          t.setName(Exec1Name)
          t
        }

        val Exec2Name = "testing executor 2"
        val exec2 = Executors.newSingleThreadExecutor { r =>
          val t = new Thread(r)
          t.setName(Exec2Name)
          t
        }

        val Exec3Name = "testing executor 3"
        val exec3 = Executors.newSingleThreadExecutor { r =>
          val t = new Thread(r)
          t.setName(Exec3Name)
          t
        }

        val nameF = IO(Thread.currentThread().getName())

        val test = nameF flatMap { outer1 =>
          val inner1F = nameF flatMap { inner1 =>
            val inner2F = nameF map { inner2 => (outer1, inner1, inner2) }

            inner2F.evalOn(ExecutionContext.fromExecutor(exec2))
          }

          inner1F.evalOn(ExecutionContext.fromExecutor(exec1)).flatMap {
            case (outer1, inner1, inner2) =>
              nameF.map(outer2 => (outer1, inner1, inner2, outer2))
          }
        }

        test.evalOn(ExecutionContext.fromExecutor(exec3)).flatMap { result =>
          IO {
            result mustEqual ((Exec3Name, Exec1Name, Exec2Name, Exec3Name))
          }
        }
      }

      "start 1000 fibers in parallel and await them all" in real {
        val input = (0 until 1000).toList

        val ioa = for {
          fibers <- input.traverse(i => IO.pure(i).start)
          _ <- fibers.traverse_(_.join.void)
        } yield ()

        ioa.as(ok)
      }

      "start 1000 fibers in series and await them all" in real {
        val input = (0 until 1000).toList
        val ioa = input.traverse(i => IO.pure(i).start.flatMap(_.join))

        ioa.as(ok)
      }

      "race many things" in real {
        val task = (0 until 100).foldLeft(IO.never[Int]) { (acc, _) =>
          IO.race(acc, IO(1)).map {
            case Left(i) => i
            case Right(i) => i
          }
        }

        task.replicateA(100).as(ok)
      }

      "round trip non-canceled through j.u.c.CompletableFuture" in ticked { implicit ticker =>
        forAll { (ioa: IO[Int]) =>
          val normalized = ioa.onCancel(IO.never)
          normalized.eqv(IO.fromCompletableFuture(IO(normalized.unsafeToCompletableFuture())))
        }
      }

      "canceled through j.u.c.CompletableFuture is errored" in ticked { implicit ticker =>
        val test =
          IO.fromCompletableFuture(IO(IO.canceled.as(-1).unsafeToCompletableFuture()))
            .handleError(_ => 42)

        test must completeAs(42)
      }

      "errors in j.u.c.CompletableFuture are not wrapped" in ticked { implicit ticker =>
        val e = new RuntimeException("stuff happened")
        val test = IO
          .fromCompletableFuture[Int](IO {
            val root = new CompletableFuture[Int]
            root.completeExceptionally(e)
            root.thenApply(_ + 1)
          })
          .attempt

        test must completeAs(Left(e))
      }

      "interrupt well-behaved blocking synchronous effect" in real {
        var interrupted = true
        val latch = new CountDownLatch(1)

        val await = IO.interruptible {
          latch.countDown()
          Thread.sleep(15000)
          interrupted = false
        }

        for {
          f <- await.start
          _ <- IO.blocking(latch.await())
          _ <- f.cancel
          _ <- IO(interrupted must beTrue)
        } yield ok
      }

      "interrupt ill-behaved blocking synchronous effect" in real {
        var interrupted = true
        val latch = new CountDownLatch(1)

        val await = IO.interruptibleMany {
          latch.countDown()

          try {
            Thread.sleep(15000)
          } catch {
            case _: InterruptedException => ()
          }

          // psych!
          try {
            Thread.sleep(15000)
          } catch {
            case _: InterruptedException => ()
          }

          // I AM INVINCIBLE
          Thread.sleep(15000)

          interrupted = false
        }

        for {
          f <- await.start
          _ <- IO.blocking(latch.await())
          _ <- f.cancel
          _ <- IO(interrupted must beTrue)
        } yield ok
      }

      "auto-cede" in real {
        val forever = IO.unit.foreverM

        val ec = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())

        val run = for {
          // Run in a tight loop on single-threaded ec so only hope of
          // seeing cancelation status is auto-cede
          fiber <- forever.start
          // Allow the tight loop to be scheduled
          _ <- IO.sleep(5.millis)
          // Only hope for the cancelation being run is auto-yielding
          _ <- fiber.cancel
        } yield true

        run.evalOn(ec).guarantee(IO(ec.shutdown())).flatMap { res => IO(res must beTrue) }
      }

      "realTimeInstant should return an Instant constructed from realTime" in ticked {
        implicit ticker =>
          val op = for {
            now <- IO.realTimeInstant
            realTime <- IO.realTime
          } yield now.toEpochMilli == realTime.toMillis

          op must completeAs(true)
      }

      "cancel all inner effects when canceled" in ticked { implicit ticker =>
        val deadlock = for {
          gate1 <- Semaphore[IO](2)
          _ <- gate1.acquireN(2)

          gate2 <- Semaphore[IO](2)
          _ <- gate2.acquireN(2)

          io = IO {
            // these finalizers never return, so this test is intentionally designed to hang
            // they flip their gates first though; this is just testing that both run in parallel
            val a = (gate1.release *> IO.never) onCancel {
              gate2.release *> IO.never
            }

            val b = (gate1.release *> IO.never) onCancel {
              gate2.release *> IO.never
            }

            a.unsafeRunAndForget()
            b.unsafeRunAndForget()
          }

          _ <- io.flatMap(_ => gate1.acquireN(2)).start
          _ <- gate2.acquireN(2) // if both are not run in parallel, then this will hang
        } yield ()

        val test = for {
          t <- IO(deadlock.unsafeToFutureCancelable())
          (f, ct) = t
          _ <- IO.fromFuture(IO(ct()))
          _ <- IO.blocking(scala.concurrent.Await.result(f, Duration.Inf))
        } yield ()

        test.attempt.map {
          case Left(t) => t.isInstanceOf[CancellationException]
          case Right(_) => false
        } must completeAs(true)
      }

    }
  }
}
