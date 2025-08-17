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
package unsafe

import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicBoolean

class SchedulerSuite extends BaseSuite {

  real("correctly handle very long sleeps") {
    // When the provided timeout in milliseconds overflows a signed 32-bit int, the implementation defaults to 1 millisecond
    IO.sleep(Long.MaxValue.nanos)
      .race(IO.sleep(100.millis))
      .map(r => assertEquals(r, Right(())))
  }
  real("use the correct max timeout") {
    IO.sleep(Int.MaxValue.millis)
      .race(IO.sleep(100.millis))
      .map(r => assertEquals(r, Right(())))
  }
  real("use high-precision time") {
    for {
      start <- IO.realTime
      times <- IO.realTime.replicateA(100)
      deltas = times.map(_ - start)
    } yield assert(deltas.exists(_.toMicros % 1000 != 0))
  }
  real("correctly calculate real time") {
    IO.realTime.product(IO(System.currentTimeMillis())).map {
      case (realTime, currentTime) =>
        assert(realTime.toMillis - currentTime <= 10L)
    }
  }
  real("cancel") {
    val scheduler = IORuntime.global.scheduler
    for {
      ref <- IO(new AtomicBoolean(true))
      cancel <- IO(scheduler.sleep(200.millis, () => ref.set(false)))
      _ <- IO.sleep(100.millis)
      _ <- IO(cancel.run())
      _ <- IO.sleep(200.millis)
      didItCancel <- IO(ref.get())
    } yield assert(didItCancel)
  }

}
