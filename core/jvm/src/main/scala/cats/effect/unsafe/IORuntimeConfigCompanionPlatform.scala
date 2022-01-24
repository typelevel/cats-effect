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

import scala.concurrent.duration.Duration
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

    val blockingCacheExpiration =
      Try(System.getProperty("cats.effect.blocking.cache.expiration"))
        .map(Duration(_))
        .getOrElse(DefaultBlockingCacheExpiration)

    apply(
      cancelationCheckThreshold,
      autoYieldThreshold,
      enhancedExceptions,
      traceBufferSize,
      shutdownHookTimeout,
      blockingCacheExpiration)
  }
}
