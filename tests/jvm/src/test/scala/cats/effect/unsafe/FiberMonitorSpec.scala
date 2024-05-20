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

package cats.effect.unsafe

import cats.effect.{BaseSpec, FiberIO, IO, Outcome}
import cats.effect.std.CountDownLatch
import cats.effect.testkit.TestInstances

import scala.concurrent.duration._

class FiberMonitorSpec extends BaseSpec with TestInstances {

  "FiberMonitor" should {

    "show only active fibers in a live snapshot" in realWithRuntime { (runtime: IORuntime) =>
      val waitingPattern = raw"cats.effect.IOFiber@[0-9a-f][0-9a-f]+ WAITING((.|\n)*)"
      val completedPattern = raw"cats.effect.IOFiber@[0-9a-f][0-9a-f]+ COMPLETED"

      for {
        cdl <- CountDownLatch[IO](1)
        fiber <- cdl.await.start // create a 'waiting' fiber
        fiberId <- IO(extractFiberId(fiber))
        _ <- IO.sleep(100.millis)

        snapshot <- IO(makeSnapshot(runtime))
        _ <- IO(snapshot must have size 2) // root and awaiting fibers
        fiberSnapshot <- IO(snapshot.filter(_.contains(fiberId)))
        _ <- IO(fiberSnapshot must have size 1) // only awaiting fiber
        _ <- IO(fiberSnapshot must containPattern(waitingPattern))

        _ <- cdl.release // allow further execution
        outcome <- fiber.join
        _ <- IO.sleep(100.millis)

        _ <- IO(outcome must beEqualTo(Outcome.succeeded[IO, Throwable, Unit](IO.unit)))
        _ <- IO(fiber.toString must beMatching(completedPattern))
        _ <- IO(makeSnapshot(runtime) must have size 1) // only root fiber
      } yield ok
    }

  }

  // keep only fibers
  private def makeSnapshot(runtime: IORuntime): List[String] = {
    val builder = List.newBuilder[String]
    runtime.fiberMonitor.liveFiberSnapshot(builder += _)
    builder.result().filter(_.startsWith("cats.effect.IOFiber"))
  }

  private def extractFiberId(fiber: FiberIO[Unit]): String = {
    val pattern = raw"cats.effect.IOFiber@([0-9a-f][0-9a-f]+) .*".r
    pattern.findAllMatchIn(fiber.toString).map(_.group(1)).next()
  }

}
