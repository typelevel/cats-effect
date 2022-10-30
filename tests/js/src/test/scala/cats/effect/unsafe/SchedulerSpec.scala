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

package cats.effect
package unsafe

import scala.concurrent.duration._

class SchedulerSpec extends BaseSpec {

  "Default scheduler" should {
    "correctly handle very long sleeps" in real {
      // When the provided timeout in milliseconds overflows a signed 32-bit int, the implementation defaults to 1 millisecond
      IO.sleep(Long.MaxValue.nanos).race(IO.sleep(100.millis)) mustEqual Right(())
    }
    "use the correct max timeout" in real {
      IO.sleep(Int.MaxValue.millis).race(IO.sleep(100.millis)) mustEqual Right(())
    }
    "use high-precision time" in real {
      for {
        start <- IO.realTime
        times <- IO.realTime.replicateA(100)
        deltas = times.map(_ - start)
      } yield deltas.exists(_.toMicros % 1000 != 0)
    }
    "correctly calculate real time" in real {
      IO.realTime.product(IO(System.currentTimeMillis())).map {
        case (realTime, currentTime) =>
          (realTime.toMillis - currentTime) should be_<=(1L)
      }
    }
  }

}
