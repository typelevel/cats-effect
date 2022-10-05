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

final case class IORuntimeConfig private (
    val cancelationCheckThreshold: Int,
    val autoYieldThreshold: Int,
    val enhancedExceptions: Boolean,
    val traceBufferSize: Int,
    val shutdownHookTimeout: Duration,
    val reportUnhandledFiberErrors: Boolean) {

  private[unsafe] def this(cancelationCheckThreshold: Int, autoYieldThreshold: Int) =
    this(
      cancelationCheckThreshold,
      autoYieldThreshold,
      IORuntimeConfig.DefaultEnhancedExceptions,
      IORuntimeConfig.DefaultTraceBufferSize,
      IORuntimeConfig.DefaultShutdownHookTimeout,
      IORuntimeConfig.DefaultReportUnhandledFiberErrors
    )

  def copy(
      cancelationCheckThreshold: Int = this.cancelationCheckThreshold,
      autoYieldThreshold: Int = this.autoYieldThreshold,
      enhancedExceptions: Boolean = this.enhancedExceptions,
      traceBufferSize: Int = this.traceBufferSize,
      shutdownHookTimeout: Duration = this.shutdownHookTimeout,
      reportUnhandledFiberErrors: Boolean = this.reportUnhandledFiberErrors): IORuntimeConfig =
    new IORuntimeConfig(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout,
      reportUnhandledFiberErrors)

  private[unsafe] def copy(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int,
      enhancedExceptions: Boolean,
      traceBufferSize: Int,
      shutdownHookTimeout: Duration): IORuntimeConfig =
    new IORuntimeConfig(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout,
      IORuntimeConfig.DefaultReportUnhandledFiberErrors)

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
      IORuntimeConfig.DefaultShutdownHookTimeout,
      IORuntimeConfig.DefaultReportUnhandledFiberErrors
    )

  // shims for binary compat
  private[unsafe] def this(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int,
      enhancedExceptions: Boolean,
      traceBufferSize: Int,
      shutdownHookTimeout: Duration) =
    this(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout,
      IORuntimeConfig.DefaultReportUnhandledFiberErrors)

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
      shutdownHookTimeout,
      reportUnhandledFiberErrors)

  private[unsafe] def copy(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int): IORuntimeConfig =
    new IORuntimeConfig(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout,
      reportUnhandledFiberErrors)

  private[effect] val traceBufferLogSize: Int =
    Math.round(Math.log(traceBufferSize.toDouble) / Math.log(2)).toInt

  def withCancelationCheckThreshold(n: Int): IORuntimeConfig =
    copy(cancelationCheckThreshold = n)

  def withAutoYieldThreshold(n: Int): IORuntimeConfig = copy(autoYieldThreshold = n)

  def withEnhancedExceptions(b: Boolean): IORuntimeConfig = copy(enhancedExceptions = b)

  def withTraceBufferSize(n: Int): IORuntimeConfig = copy(traceBufferSize = n)

  def withShutdownHookTimeout(d: Duration): IORuntimeConfig = copy(shutdownHookTimeout = d)

  def withReportUnhandledFiberErrors(b: Boolean): IORuntimeConfig =
    copy(reportUnhandledFiberErrors = b)
}

object IORuntimeConfig extends IORuntimeConfigCompanionPlatform {

  // these have to be defs because we forward-reference them from the companion platform
  private[unsafe] def DefaultEnhancedExceptions = true
  private[unsafe] def DefaultTraceBufferSize = 16
  private[unsafe] def DefaultShutdownHookTimeout = Duration.Inf
  private[unsafe] def DefaultReportUnhandledFiberErrors = true

  def apply(): IORuntimeConfig = Default

  def apply(cancelationCheckThreshold: Int, autoYieldThreshold: Int): IORuntimeConfig =
    apply(
      cancelationCheckThreshold,
      autoYieldThreshold,
      DefaultEnhancedExceptions,
      DefaultTraceBufferSize,
      DefaultShutdownHookTimeout)

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
      shutdownHookTimeout: Duration): IORuntimeConfig =
    apply(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout,
      DefaultReportUnhandledFiberErrors)

  def apply(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int,
      enhancedExceptions: Boolean,
      traceBufferSize: Int,
      shutdownHookTimeout: Duration,
      reportUnhandledFiberErrors: Boolean): IORuntimeConfig = {
    if (autoYieldThreshold % cancelationCheckThreshold == 0)
      new IORuntimeConfig(
        cancelationCheckThreshold,
        autoYieldThreshold,
        enhancedExceptions,
        1 << Math.round(Math.log(traceBufferSize.toDouble) / Math.log(2)).toInt,
        shutdownHookTimeout,
        reportUnhandledFiberErrors
      )
    else
      throw new AssertionError(
        s"Auto yield threshold $autoYieldThreshold must be a multiple of cancelation check threshold $cancelationCheckThreshold")
  }
}
