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

import cats.implicits._
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import unsafe.{HashedWheelTimerScheduler, Scheduler}
import unsafe.HashedWheelTimerScheduler._

import org.scalacheck.Gen
import org.scalacheck.Arbitrary.arbitrary

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

class HashedWheelTimerSchedulerSpec extends Specification with ScalaCheck with Runners {

  val tolerance: FiniteDuration =
    FiniteDuration.apply((1.5 * defaultResolution.toMillis).toLong, TimeUnit.MILLISECONDS)

  var scheduler: Scheduler = null
  var shutdown: () => Unit = null

  "hashed wheel timer" should {

    // "complete within allowed time period" in real {

    //   val delay = 500.millis

    //   IO.race(
    //     IO.async((cb: Either[Throwable, Unit] => Unit) => {
    //       // runtime().scheduler.sleep(delay, () => cb(Right(())))
    //       scheduler.sleep(delay, () => cb(Right(())))
    //       IO.pure(None)
    //     }),
    //     IO.sleep(delay + tolerance)
    //   ).flatMap { result =>
    //     IO {
    //       result mustEqual (Left(()))
    //     }
    //   }

    // }
    //

    "complete many not before scheduled time" in realProp(
      Gen.listOfN(100, durationGen.map(_ + defaultResolution))) { delays =>
      delays
        .parTraverse_ { delay =>
          for {
            t1 <- IO(scheduler.monotonicNanos())
            _ <- IO.async((cb: Either[Throwable, Unit] => Unit) => {
              // runtime().scheduler.sleep(delay, () => cb(Right(())))
              scheduler.sleep(delay, () => cb(Right(())))
              IO.pure(None)
            })
            t2 <- IO(scheduler.monotonicNanos())
            actual = (t2 - t1).nanos
            // _ <- IO.println(s"$actual $delay")
            _ <- IO(assert(actual >= delay))
          } yield ()
        }
        .attempt
        .flatMap { result =>
          IO {
            result mustEqual (Right(()))
          }
        }
    }

    "complete many within allowed time period" in realProp(Gen.listOfN(100, durationGen)) {
      delays =>
        delays
          .parTraverse_ { delay =>
            for {
              t1 <- IO(scheduler.monotonicNanos())
              _ <-
                IO.async((cb: Either[Throwable, Unit] => Unit) => {
                  // runtime().scheduler.sleep(delay, () => cb(Right(())))
                  scheduler.sleep(delay, () => cb(Right(())))
                  IO.pure(None)
                }).timeout(delay + tolerance)
              t2 <- IO(scheduler.monotonicNanos())
              actual = (t2 - t1).nanos
              _ <- IO(assert(actual <= delay + tolerance))
            } yield ()
          }
          .attempt
          .flatMap { result =>
            IO {
              result mustEqual (Right(()))
            }
          }
    }

  }

  override def beforeAll(): Unit = {
    val (s, close) = Scheduler.createDefaultScheduler()
    scheduler = s
    shutdown = close
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    shutdown()
    super.afterAll()
  }

  private def durationGen: Gen[FiniteDuration] = Gen.choose(0L, 1000L).map(n => n.millis)

}
