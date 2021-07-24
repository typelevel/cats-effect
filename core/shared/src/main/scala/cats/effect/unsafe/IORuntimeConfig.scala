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
    val cancelationCheckThreshold: Int,
    val autoYieldThreshold: Int,
    val enhancedExceptions: Boolean,
    val traceBufferSize: Int) {

  def this(cancelationCheckThreshold: Int, autoYieldThreshold: Int) =
    this(
      cancelationCheckThreshold,
      autoYieldThreshold,
      IORuntimeConfig.DefaultEnhancedExceptions,
      IORuntimeConfig.DefaultTraceBufferSize)

  private[effect] val traceBufferLogSize: Int =
    Math.round(Math.log(traceBufferSize.toDouble) / Math.log(2)).toInt

  // shim for binary compat
  private[unsafe] def copy(
      cancelationCheckThreshold: Int = this.cancelationCheckThreshold,
      autoYieldThreshold: Int = this.autoYieldThreshold): IORuntimeConfig =
    new IORuntimeConfig(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize)
}

object IORuntimeConfig extends IORuntimeConfigCompanionPlatform {

  // these have to be defs because we forward-reference them from the companion platform
  private[unsafe] def DefaultEnhancedExceptions = true
  private[unsafe] def DefaultTraceBufferSize = 16

  def apply(): IORuntimeConfig = Default

  def apply(cancelationCheckThreshold: Int, autoYieldThreshold: Int): IORuntimeConfig =
    apply(
      cancelationCheckThreshold,
      autoYieldThreshold,
      DefaultEnhancedExceptions,
      DefaultTraceBufferSize)

  def apply(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int,
      enhancedExceptions: Boolean,
      traceBufferSize: Int): IORuntimeConfig = {
    if (autoYieldThreshold % cancelationCheckThreshold == 0)
      new IORuntimeConfig(
        cancelationCheckThreshold,
        autoYieldThreshold,
        enhancedExceptions,
        2 << (Math.round(Math.log(traceBufferSize.toDouble) / Math.log(2)).toInt - 1))
    else
      throw new AssertionError(
        s"Auto yield threshold $autoYieldThreshold must be a multiple of cancelation check threshold $cancelationCheckThreshold")
  }
}
