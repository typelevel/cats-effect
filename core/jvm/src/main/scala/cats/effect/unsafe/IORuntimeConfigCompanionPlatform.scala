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

    val reportUnhandledFiberErrors =
      Try(System.getProperty("cats.effect.report.unhandledFiberErrors").toBoolean)
        .getOrElse(DefaultReportUnhandledFiberErrors)

    val cpuStarvationCheckInterval =
      Try(System.getProperty("cats.effect.cpu.starvation.check.interval"))
        .map(Duration(_))
        .flatMap { d => Try(d.asInstanceOf[FiniteDuration]) }
        .getOrElse(DefaultCpuStarvationCheckInterval)

    val cpuStarvationCheckInitialDelay =
      Try(System.getProperty("cats.effect.cpu.starvation.check.initialDelay"))
        .map(Duration(_))
        .getOrElse(DefaultCpuStarvationCheckInitialDelay)

    val cpuStarvationCheckThreshold =
      Try(System.getProperty("cats.effect.cpu.starvation.check.threshold"))
        .flatMap(p => Try(p.toDouble))
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
