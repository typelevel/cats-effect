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

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

private[unsafe] abstract class IORuntimeConfigCompanionPlatform { this: IORuntimeConfig.type =>
  // TODO make the cancelation and auto-yield properties have saner names
  protected final val Default: IORuntimeConfig = {
    val cancelationCheckThreshold = process
      .env("CATS_EFFECT_CANCELATION_CHECK_THRESHOLD")
      .flatMap(x => Try(x.toInt).toOption)
      .getOrElse(512)

    val autoYieldThreshold = process
      .env("CATS_EFFECT_AUTO_YIELD_THRESHOLD_MULTIPLIER")
      .flatMap(x => Try(x.toInt).toOption)
      .getOrElse(2) * cancelationCheckThreshold

    val enhancedExceptions = process
      .env("CATS_EFFECT_TRACING_EXCEPTIONS_ENHANCED")
      .flatMap(x => Try(x.toBoolean).toOption)
      .getOrElse(DefaultEnhancedExceptions)

    val traceBufferSize = process
      .env("CATS_EFFECT_TRACING_BUFFER_SIZE")
      .flatMap(x => Try(x.toInt).toOption)
      .getOrElse(DefaultTraceBufferSize)

    val shutdownHookTimeout = process
      .env("CATS_EFFECT_SHUTDOWN_HOOK_TIMEOUT")
      .flatMap(x => Try(Duration(x)).toOption)
      .getOrElse(DefaultShutdownHookTimeout)

    val reportUnhandledFiberErrors = process
      .env("CATS_EFFECT_REPORT_UNHANDLED_FIBER_ERRORS")
      .flatMap(x => Try(x.toBoolean).toOption)
      .getOrElse(DefaultReportUnhandledFiberErrors)

    val cpuStarvationCheckInterval =
      process
        .env("CATS_EFFECT_CPU_STARVATION_CHECK_INTERVAL")
        .map(Duration(_))
        .flatMap { d => Try(d.asInstanceOf[FiniteDuration]).toOption }
        .getOrElse(DefaultCpuStarvationCheckInterval)

    val cpuStarvationCheckInitialDelay =
      process
        .env("CATS_EFFECT_CPU_STARVATION_CHECK_INITIAL_DELAY")
        .map(Duration(_))
        .getOrElse(DefaultCpuStarvationCheckInitialDelay)

    val cpuStarvationCheckThreshold =
      process
        .env("CATS_EFFECT_CPU_STARVATION_CHECK_THRESHOLD")
        .flatMap(p => Try(p.toDouble).toOption)
        .getOrElse(DefaultCpuStarvationCheckThreshold)

    apply(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout,
      reportUnhandledFiberErrors,
      cpuStarvationCheckInterval,
      cpuStarvationCheckInitialDelay,
      cpuStarvationCheckThreshold
    )
  }
}
