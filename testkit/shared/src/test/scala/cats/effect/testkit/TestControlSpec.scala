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
package testkit

import org.specs2.mutable.Specification

import scala.concurrent.duration._

class TestControlSpec extends Specification {

  val simple = IO.unit

  val ceded = IO.cede.replicateA(10) *> IO.unit

  val longSleeps = for {
    first <- IO.monotonic
    _ <- IO.sleep(1.hour)
    second <- IO.monotonic
    _ <- IO.race(IO.sleep(1.day), IO.sleep(1.day + 1.nanosecond))
    third <- IO.monotonic
  } yield (first.toCoarsest, second.toCoarsest, third.toCoarsest)

  val deadlock = IO.never

  "execute" should {
    "run a simple IO" in {
      TestControl.execute(simple) { (control, results) =>
        results() must beNone
        control.tick()
        results() must beSome(beRight(()))
      }
    }

    "run a ceded IO in a single tick" in {
      TestControl.execute(simple) { (control, results) =>
        results() must beNone
        control.tick()
        results() must beSome(beRight(()))
      }
    }

    "run an IO with long sleeps" in {
      TestControl.execute(longSleeps) { (control, results) =>
        results() must beNone

        control.tick()
        results() must beNone
        control.nextInterval() mustEqual 1.hour

        control.advanceAndTick(1.hour)
        results() must beNone
        control.nextInterval() mustEqual 1.day

        control.advanceAndTick(1.day)
        results() must beSome(beRight((0.nanoseconds, 1.hour, 25.hours)))
      }
    }

    "detect a deadlock" in {
      TestControl.execute(deadlock) { (control, results) =>
        results() must beNone
        control.tick()
        control.isDeadlocked() must beTrue
        results() must beNone
      }
    }
  }

  "executeFully" should {
    "run a simple IO" in {
      TestControl.executeFully(simple) must beSome(beRight(()))
    }

    "run an IO with long sleeps" in {
      TestControl.executeFully(longSleeps) must beSome(
        beRight((0.nanoseconds, 1.hour, 25.hours)))
    }

    "detect a deadlock" in {
      TestControl.executeFully(deadlock) must beNone
    }
  }
}
