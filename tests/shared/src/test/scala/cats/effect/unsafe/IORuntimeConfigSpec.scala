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

package cats.effect
package unsafe

import scala.util.Try

class IORuntimeConfigSpec extends BaseSpec {

  "IORuntimeConfig" should {

    "Reject invalid values of cancelation check- and auto yield threshold" in {
      Try(
        IORuntimeConfig(
          cancelationCheckThreshold = -1,
          autoYieldThreshold = -1)) must beFailedTry
      Try(
        IORuntimeConfig(
          cancelationCheckThreshold = -1,
          autoYieldThreshold = -2)) must beFailedTry
      Try(
        IORuntimeConfig(cancelationCheckThreshold = 0, autoYieldThreshold = 2)) must beFailedTry
      Try(
        IORuntimeConfig(cancelationCheckThreshold = 1, autoYieldThreshold = 1)) must beFailedTry
      Try(
        IORuntimeConfig(cancelationCheckThreshold = 2, autoYieldThreshold = 3)) must beFailedTry
      Try(
        IORuntimeConfig(cancelationCheckThreshold = 4, autoYieldThreshold = 2)) must beFailedTry
      // these are fine:
      IORuntimeConfig(cancelationCheckThreshold = 1, autoYieldThreshold = 2)
      IORuntimeConfig(cancelationCheckThreshold = 1, autoYieldThreshold = 3)
      IORuntimeConfig(cancelationCheckThreshold = 2, autoYieldThreshold = 2)
      IORuntimeConfig(cancelationCheckThreshold = 2, autoYieldThreshold = 4)
      ok
    }

    "Reject invalid values even in the copy method" in {
      val cfg = IORuntimeConfig(cancelationCheckThreshold = 1, autoYieldThreshold = 2)
      Try(cfg.copy(cancelationCheckThreshold = 0)) must beFailedTry
      Try(cfg.copy(cancelationCheckThreshold = -1)) must beFailedTry
      Try(cfg.copy(autoYieldThreshold = 1)) must beFailedTry
      Try(cfg.copy(cancelationCheckThreshold = 2, autoYieldThreshold = 3)) must beFailedTry
    }
  }
}
