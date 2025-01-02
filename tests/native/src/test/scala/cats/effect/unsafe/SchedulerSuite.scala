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

class SchedulerSuite extends BaseSuite {

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
        assert(realTime.toMillis - currentTime <= 1L)
    }
  }

  real("sleep for correct duration") {
    val duration = 1500.millis
    IO.sleep(duration).timed.map(r => assert(r._1 >= duration))
  }

}
