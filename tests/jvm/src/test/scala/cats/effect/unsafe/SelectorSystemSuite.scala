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

package cats.effect.unsafe

import cats.effect.BaseSuite

import scala.concurrent.duration._

class SelectorSystemSuite extends BaseSuite {

  testUnit("block for a positive sub-millisecond timeout") {
    val system = SelectorSystem()
    val poller = system.makePoller()
    val timeout = 500.micros

    try {
      val shortestWait = List
        .fill(10) {
          val start = System.nanoTime()
          val _ = system.poll(poller, timeout.toNanos)
          (System.nanoTime() - start).nanos
        }
        .min

      assert(
        shortestWait >= timeout,
        s"poll returned after $shortestWait for a $timeout timeout")
    } finally {
      system.closePoller(poller)
      system.close()
    }
  }
}
