/*
 * Copyright 2020-2022 Typelevel
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

import cats.effect.{BaseSpec, IO}
import cats.effect.testkit.{TestControl, TestInstances}

import scala.concurrent.duration._

class FiberMonitorSpec extends BaseSpec with TestInstances {

  override def executionTimeout: FiniteDuration = 60.seconds

  "FiberMonitor" should {

    "show only active fibers in a live snapshot" in real {
      val program = IO.println("print text").start.flatMap(_.join)
      val pattern = raw"cats.effect.IOFiber@[0-9a-f][0-9a-f]+ ACTIVE((.|\n)*)"

      TestControl.execute(program).flatMap { control =>
        for {
          r1 <- control.results
          _ <- IO(r1 must beNone)

          _ <- IO(makeSnapshot(control) must beEmpty)

          _ <- control.tickOne
          _ <- control.tickOne

          snapshot <- IO(makeSnapshot(control))

          // _ <- IO.println(snapshot.mkString("\n"))

          _ <- IO(snapshot must have size 2)
          _ <- IO(snapshot must containPattern(pattern))

          _ <- control.tickAll

          _ <- IO(makeSnapshot(control) must beEmpty)

          r2 <- control.results
          _ <- IO(r2 must beSome)
        } yield ok
      }
    }
  }

  // keep only non-empty lines
  private def makeSnapshot[A](control: TestControl[A]): List[String] =
    control.liveFiberSnapshot.filter(_.trim.nonEmpty)

}
