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

package cats.effect

import cats.syntax.all._

import org.scalacheck.Prop.forAll

import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.{CountDownLatch, Executors}

abstract class IOPlatformSpecification extends Specification with ScalaCheck with Runners {

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

      "round trip through j.u.c.CompletableFuture" in ticked { implicit ticker =>
        forAll { (ioa: IO[Int]) =>
          ioa.eqv(IO.fromCompletableFuture(IO(ioa.unsafeToCompletableFuture())))
        }
      }

      "interrupt well-behaved blocking synchronous effect" in real {
        var interrupted = true
        val latch = new CountDownLatch(1)

        val await = IO.interruptible(false) {
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

        val await = IO.interruptible(true) {
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
          //Run in a tight loop on single-threaded ec so only hope of
          //seeing cancellation status is auto-cede
          fiber <- forever.start
          //Allow the tight loop to be scheduled
          _ <- IO.sleep(5.millis)
          //Only hope for the cancellation being run is auto-yielding
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

    }
  }
}
