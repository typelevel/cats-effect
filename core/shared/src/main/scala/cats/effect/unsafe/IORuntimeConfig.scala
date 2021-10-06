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

import scala.concurrent.duration._

final case class IORuntimeConfig private (
    val cancelationCheckThreshold: Int,
    val autoYieldThreshold: Int,
    val enhancedExceptions: Boolean,
    val traceBufferSize: Int,
    val shutdownHookTimeout: Duration) {

  @deprecated(
    "Use IORuntimeConfig.apply(cancelationCheckThreshold, autoYieldThreshold, enhancedExceptions, traceBufferSize, shutdownHookTimeout",
    "3.3.0")
  def this(cancelationCheckThreshold: Int, autoYieldThreshold: Int) =
    this(
      cancelationCheckThreshold,
      autoYieldThreshold,
      IORuntimeConfig.DefaultEnhancedExceptions,
      IORuntimeConfig.DefaultTraceBufferSize,
      IORuntimeConfig.DefaultShutdownHookTimeout)

  def copy(
      cancelationCheckThreshold: Int = this.cancelationCheckThreshold,
      autoYieldThreshold: Int = this.autoYieldThreshold,
      enhancedExceptions: Boolean = this.enhancedExceptions,
      traceBufferSize: Int = this.traceBufferSize,
      shutdownHookTimeout: Duration = this.shutdownHookTimeout): IORuntimeConfig =
    new IORuntimeConfig(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout)

  // shims for binary compat
  private[unsafe] def this(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int,
      enhancedExceptions: Boolean,
      traceBufferSize: Int) =
    this(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      IORuntimeConfig.DefaultShutdownHookTimeout)

  private[unsafe] def copy(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int,
      enhancedExceptions: Boolean,
      traceBufferSize: Int): IORuntimeConfig =
    new IORuntimeConfig(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout)

  private[unsafe] def copy(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int): IORuntimeConfig =
    new IORuntimeConfig(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout)

  private[effect] val traceBufferLogSize: Int =
    Math.round(Math.log(traceBufferSize.toDouble) / Math.log(2)).toInt
}

object IORuntimeConfig extends IORuntimeConfigCompanionPlatform {

  // these have to be defs because we forward-reference them from the companion platform
  private[unsafe] def DefaultEnhancedExceptions = true
  private[unsafe] def DefaultTraceBufferSize = 16
  private[unsafe] def DefaultShutdownHookTimeout = Duration.Inf

  def apply(): IORuntimeConfig = Default

  @deprecated(
    "Use IORuntimeConfig.apply(cancelationCheckThreshold, autoYieldThreshold, enhancedExceptions, traceBufferSize, shutdownHookTimeout",
    "3.3.0")
  def apply(cancelationCheckThreshold: Int, autoYieldThreshold: Int): IORuntimeConfig =
    apply(
      cancelationCheckThreshold,
      autoYieldThreshold,
      DefaultEnhancedExceptions,
      DefaultTraceBufferSize,
      DefaultShutdownHookTimeout)

  @deprecated(
    "Use IORuntimeConfig.apply(cancelationCheckThreshold, autoYieldThreshold, enhancedExceptions, traceBufferSize, shutdownHookTimeout",
    "3.3.0")
  def apply(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int,
      enhancedExceptions: Boolean,
      traceBufferSize: Int): IORuntimeConfig =
    apply(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      DefaultShutdownHookTimeout)

  def apply(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int,
      enhancedExceptions: Boolean,
      traceBufferSize: Int,
      shutdownHookTimeout: Duration): IORuntimeConfig = {
    if (autoYieldThreshold % cancelationCheckThreshold == 0)
      new IORuntimeConfig(
        cancelationCheckThreshold,
        autoYieldThreshold,
        enhancedExceptions,
        1 << Math.round(Math.log(traceBufferSize.toDouble) / Math.log(2)).toInt,
        shutdownHookTimeout)
    else
      throw new AssertionError(
        s"Auto yield threshold $autoYieldThreshold must be a multiple of cancelation check threshold $cancelationCheckThreshold")
  }
}
