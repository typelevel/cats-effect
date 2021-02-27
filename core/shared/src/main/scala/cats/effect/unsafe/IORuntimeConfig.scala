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
package unsafe

final case class IORuntimeConfig private (
    val cancellationCheckThreshold: Int,
    val autoYieldThreshold: Int)

object IORuntimeConfig extends IORuntimeConfigCompanionPlatform {

  def apply(cancellationCheckThreshold: Int, autoYieldThreshold: Int): IORuntimeConfig = {
    if (autoYieldThreshold % cancellationCheckThreshold == 0)
      new IORuntimeConfig(cancellationCheckThreshold, autoYieldThreshold)
    else
      throw new AssertionError(
        s"Auto yield threshold $autoYieldThreshold must be a multiple of cancellation check threshold $cancellationCheckThreshold")
  }
}
