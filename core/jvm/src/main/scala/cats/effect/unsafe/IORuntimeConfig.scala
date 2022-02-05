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

package cats.effect.unsafe

import scala.concurrent.duration._
import scala.util.Try

import IORuntimeConfig._
import IORuntimeConfigDefaults._

final case class IORuntimeConfig private (
    val cancelationCheckThreshold: Int,
    val autoYieldThreshold: Int,
    val enhancedExceptions: Boolean,
    val traceBufferSize: Int,
    val shutdownHookTimeout: Duration,
    val runtimeBlockingExpiration: Duration
) {
  private[unsafe] def this(cancelationCheckThreshold: Int, autoYieldThreshold: Int) =
    this(
      cancelationCheckThreshold,
      autoYieldThreshold,
      DefaultEnhancedExceptions,
      DefaultTraceBufferSize,
      DefaultShutdownHookTimeout,
      DefaultRuntimeBlockingExpiration
    )

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
      DefaultShutdownHookTimeout,
      DefaultRuntimeBlockingExpiration
    )

  private[unsafe] def this(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int,
      enhancedExceptions: Boolean,
      traceBufferSize: Int,
      shutdownHookTimeout: Duration
  ) = this(
    cancelationCheckThreshold,
    autoYieldThreshold,
    enhancedExceptions,
    traceBufferSize,
    shutdownHookTimeout,
    DefaultRuntimeBlockingExpiration
  )

  def copy(
      cancelationCheckThreshold: Int = this.cancelationCheckThreshold,
      autoYieldThreshold: Int = this.autoYieldThreshold,
      enhancedExceptions: Boolean = this.enhancedExceptions,
      traceBufferSize: Int = this.traceBufferSize,
      shutdownHookTimeout: Duration = this.shutdownHookTimeout,
      runtimeBlockingExpiration: Duration = this.runtimeBlockingExpiration): IORuntimeConfig =
    new IORuntimeConfig(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout,
      runtimeBlockingExpiration
    )

  private[unsafe] def copy(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int): IORuntimeConfig =
    new IORuntimeConfig(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout,
      runtimeBlockingExpiration
    )

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
      runtimeBlockingExpiration
    )

  private[unsafe] def copy(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int,
      enhancedExceptions: Boolean,
      traceBufferSize: Int,
      shutdownHookTimeout: Duration
  ): IORuntimeConfig =
    new IORuntimeConfig(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout,
      runtimeBlockingExpiration
    )

  private[effect] val traceBufferLogSize: Int =
    Math.round(Math.log(traceBufferSize.toDouble) / Math.log(2)).toInt
}

private[unsafe] abstract class IORuntimeConfigCompanionPlatform { self: IORuntimeConfig.type =>
}

object IORuntimeConfig extends IORuntimeConfigCompanionPlatform {
  def apply(): IORuntimeConfig = {
    // TODO make the cancelation and auto-yield properties have saner names
    val cancelationCheckThreshold =
      Try(System.getProperty("cats.effect.cancelation.check.threshold").toInt).getOrElse(512)

    val autoYieldThreshold =
      Try(System.getProperty("cats.effect.auto.yield.threshold.multiplier").toInt)
        .getOrElse(2) * cancelationCheckThreshold

    val enhancedExceptions =
      Try(System.getProperty("cats.effect.tracing.exceptions.enhanced").toBoolean)
        .getOrElse(DefaultEnhancedExceptions)

    val traceBufferSize =
      Try(System.getProperty("cats.effect.tracing.buffer.size").toInt)
        .getOrElse(DefaultTraceBufferSize)

    val shutdownHookTimeout =
      Try(System.getProperty("cats.effect.shutdown.hook.timeout"))
        .map(Duration(_))
        .getOrElse(DefaultShutdownHookTimeout)

    val runtimeBlockingExpiration =
      Try(System.getProperty("cats.effect.runtime.blocking.expiration"))
        .map(Duration(_))
        .getOrElse(DefaultRuntimeBlockingExpiration)

    apply(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout,
      runtimeBlockingExpiration)
  }

  def apply(cancelationCheckThreshold: Int, autoYieldThreshold: Int): IORuntimeConfig =
    apply(
      cancelationCheckThreshold,
      autoYieldThreshold,
      DefaultEnhancedExceptions,
      DefaultTraceBufferSize,
      DefaultShutdownHookTimeout,
      DefaultRuntimeBlockingExpiration
    )

  def apply(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int,
      enhancedExceptions: Boolean,
      traceBufferSize: Int
  ): IORuntimeConfig =
    apply(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      DefaultShutdownHookTimeout,
      DefaultRuntimeBlockingExpiration
    )

  def apply(
      cancelationCheckThreshold: Int,
      autoYieldThreshold: Int,
      enhancedExceptions: Boolean,
      traceBufferSize: Int,
      shutdownHookTimeout: Duration
  ): IORuntimeConfig =
    apply(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout,
      DefaultRuntimeBlockingExpiration
    )

  private final val DefaultRuntimeBlockingExpiration: Duration = 60.seconds
}
