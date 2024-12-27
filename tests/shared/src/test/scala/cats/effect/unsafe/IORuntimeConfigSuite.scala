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

class IORuntimeConfigSuite extends BaseSuite {

  test("Reject invalid values of cancelation check- and auto yield threshold") {
    intercept[IllegalArgumentException](
      IORuntimeConfig(cancelationCheckThreshold = -1, autoYieldThreshold = -1))
    intercept[IllegalArgumentException](
      IORuntimeConfig(cancelationCheckThreshold = -1, autoYieldThreshold = -2))
    intercept[IllegalArgumentException](
      IORuntimeConfig(cancelationCheckThreshold = 0, autoYieldThreshold = 2))
    intercept[IllegalArgumentException](
      IORuntimeConfig(cancelationCheckThreshold = 1, autoYieldThreshold = 1))
    intercept[IllegalArgumentException](
      IORuntimeConfig(cancelationCheckThreshold = 2, autoYieldThreshold = 3))
    intercept[IllegalArgumentException](
      IORuntimeConfig(cancelationCheckThreshold = 4, autoYieldThreshold = 2))
    // these are fine:
    IORuntimeConfig(cancelationCheckThreshold = 1, autoYieldThreshold = 2)
    IORuntimeConfig(cancelationCheckThreshold = 1, autoYieldThreshold = 3)
    IORuntimeConfig(cancelationCheckThreshold = 2, autoYieldThreshold = 2)
    IORuntimeConfig(cancelationCheckThreshold = 2, autoYieldThreshold = 4)
  }

  test("Reject invalid values even in the copy method") {
    val cfg = IORuntimeConfig(cancelationCheckThreshold = 1, autoYieldThreshold = 2)
    intercept[IllegalArgumentException](cfg.copy(cancelationCheckThreshold = 0))
    intercept[IllegalArgumentException](cfg.copy(cancelationCheckThreshold = -1))
    intercept[IllegalArgumentException](cfg.copy(autoYieldThreshold = 1))
    intercept[IllegalArgumentException](
      cfg.copy(cancelationCheckThreshold = 2, autoYieldThreshold = 3))
  }
}
