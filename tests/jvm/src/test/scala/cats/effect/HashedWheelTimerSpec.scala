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

import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import unsafe.Scheduler

import scala.concurrent.duration._

class HashedWheelTimerSpec extends Specification with ScalaCheck with Runners {

  val tolerance: FiniteDuration = 200.millis

  val scheduler = Scheduler.createDefaultScheduler()._1

  "hashed wheel timer" should {

    //TODO similar test but with lots of fibers concurrently
    //TODO property test where we gen different delays?
    "complete within allowed time period" in real {

      val delay = 500.millis

      IO.race(
        IO.async((cb: Either[Throwable, Unit] => Unit) => {
          // runtime().scheduler.sleep(delay, () => cb(Right(())))
          scheduler.sleep(delay, () => cb(Right(())))
          IO.pure(None)
        }),
        IO.sleep(delay + tolerance)
      ).flatMap { result =>
        IO {
          result mustEqual (Left(()))
        }
      }

    }
  }

}
