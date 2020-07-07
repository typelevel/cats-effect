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

import cats.Show
import cats.implicits._

import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.{CountDownLatch, Executors}

abstract class IOPlatformSpecification extends Specification {

  def platformSpecs = {
    "shift delay evaluation within evalOn" in {
      val Exec1Name = "testing executor 1"
      val exec1 = Executors newSingleThreadExecutor { r =>
        val t = new Thread(r)
        t.setName(Exec1Name)
        t
      }

      val Exec2Name = "testing executor 2"
      val exec2 = Executors newSingleThreadExecutor { r =>
        val t = new Thread(r)
        t.setName(Exec2Name)
        t
      }

      val Exec3Name = "testing executor 3"
      val exec3 = Executors newSingleThreadExecutor { r =>
        val t = new Thread(r)
        t.setName(Exec3Name)
        t
      }

      val nameF = IO(Thread.currentThread().getName())

      val test = nameF flatMap { outer1 =>
        val inner1F = nameF flatMap { inner1 =>
          val inner2F = nameF map { inner2 =>
            (outer1, inner1, inner2)
          }

          inner2F.evalOn(ExecutionContext.fromExecutor(exec2))
        }

        inner1F.evalOn(ExecutionContext.fromExecutor(exec1)) flatMap {
          case (outer1, inner1, inner2) =>
            nameF.map(outer2 => (outer1, inner1, inner2, outer2))
        }
      }

      implicit val t4s: Show[(String, String, String, String)] =
        Show.fromToString

      var result: Either[Throwable, (String, String, String, String)] = null
      val latch = new CountDownLatch(1)

      // this test is weird because we're making our own contexts, so we can't use TestContext at all
      test.unsafeRunAsync(ExecutionContext.fromExecutor(exec3), UnsafeTimer.fromScheduledExecutor(Executors.newScheduledThreadPool(1))) { e =>
        result = e
        latch.countDown()
      }

      latch.await()
      result must beRight((Exec3Name, Exec1Name, Exec2Name, Exec3Name))
    }

    "start 1000 fibers in parallel and await them all" in {
      val input = (0 until 1000).toList

      val ioa = for {
        fibers <- input.traverse(i => IO.pure(i).start)
        _ <- fibers.traverse_(_.join.void)
      } yield ()

      unsafeRunRealistic(ioa) must beSome
    }

    "start 1000 fibers in series and await them all" in {
      val input = (0 until 1000).toList
      val ioa = input.traverse(i => IO.pure(i).start.flatMap(_.join))

      unsafeRunRealistic(ioa) must beSome
    }
  }

  def unsafeRunRealistic[A](ioa: IO[A]): Option[A] = {
    // TODO this code is now in 3 places; should be in 1
    val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), { (r: Runnable) =>
      val t = new Thread(r)
      t.setDaemon(true)
      t
    })

    val ctx = ExecutionContext.fromExecutor(executor)

    val scheduler = Executors newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r)
      t.setName("io-scheduler")
      t.setDaemon(true)
      t.setPriority(Thread.MAX_PRIORITY)
      t
    }

    val timer = UnsafeTimer.fromScheduledExecutor(scheduler)

    try {
      ioa.unsafeRunTimed(10.seconds, ctx, timer)
    } finally {
      executor.shutdown()
      scheduler.shutdown()
    }
  }
}
