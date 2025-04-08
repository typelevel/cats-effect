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

import org.scalacheck.Prop.forAll

import scala.concurrent.ExecutionContext

import java.util.concurrent.{CompletableFuture, CountDownLatch, ExecutorService, Executors}

trait IOPlatformSuite extends IOConcurrencySuite { this: BaseScalaCheckSuite =>

  def platformTests() = {
    concurrencyTests()

    tickedProperty("round trip non-canceled through j.u.c.CompletableFuture") {
      implicit ticker =>
        forAll { (ioa: IO[Int]) =>
          val normalized = ioa.onCancel(IO.never)
          assertEqv(
            normalized,
            IO.fromCompletableFuture(IO(normalized.unsafeToCompletableFuture())))
        }
    }

    ticked("canceled through j.u.c.CompletableFuture is errored") { implicit ticker =>
      val test =
        IO.fromCompletableFuture(IO(IO.canceled.as(-1).unsafeToCompletableFuture()))
          .handleError(_ => 42)

      assertCompleteAs(test, 42)
    }

    ticked("errors in j.u.c.CompletableFuture are not wrapped") { implicit ticker =>
      val e = new RuntimeException("stuff happened")
      val test = IO
        .fromCompletableFuture[Int](IO {
          val root = new CompletableFuture[Int]
          root.completeExceptionally(e)
          root.thenApply(_ + 1)
        })
        .attempt

      assertCompleteAs(test, Left(e))
    }

    real("interrupt well-behaved blocking synchronous effect") {
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
        _ <- IO(assert(interrupted))
      } yield ()
    }

    real("interrupt ill-behaved blocking synchronous effect") {
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
        _ <- IO(assert(interrupted))
      } yield ()
    }

    ticked("realTimeInstant should return an Instant constructed from realTime") {
      implicit ticker =>
        val op = for {
          now <- IO.realTimeInstant
          realTime <- IO.realTime
        } yield now.toEpochMilli == realTime.toMillis

        assertCompleteAs(op, true)
    }

    if (javaMajorVersion >= 21)
      real("block in-place on virtual threads") {
        val loomExec = classOf[Executors]
          .getDeclaredMethod("newVirtualThreadPerTaskExecutor")
          .invoke(null)
          .asInstanceOf[ExecutorService]

        val loomEc = ExecutionContext.fromExecutor(loomExec)

        IO.blocking {
          classOf[Thread]
            .getDeclaredMethod("isVirtual")
            .invoke(Thread.currentThread())
            .asInstanceOf[Boolean]
        }.evalOn(loomEc)
          .map(assert(_))
      }
    // else
    // "block in-place on virtual threads" in skipped("virtual threads not supported")

  }
}
