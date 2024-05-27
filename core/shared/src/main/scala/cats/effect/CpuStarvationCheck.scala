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

import cats.effect.metrics.{CpuStarvationMetrics, CpuStarvationWarningMetrics}
import cats.effect.std.Console
import cats.effect.unsafe.IORuntimeConfig
import cats.syntax.all._

import scala.concurrent.duration.{Duration, FiniteDuration}

private[effect] object CpuStarvationCheck extends CpuStarvationCheckPlatform {

  def run(
      runtimeConfig: IORuntimeConfig,
      metrics: CpuStarvationMetrics,
      onCpuStarvationWarn: CpuStarvationWarningMetrics => IO[Unit]): IO[Nothing] = {
    import runtimeConfig._

    val threshold = cpuStarvationCheckInterval * (1 + cpuStarvationCheckThreshold)

    def go(initial: FiniteDuration): IO[Nothing] =
      IO.sleep(cpuStarvationCheckInterval) >> IO.monotonic.flatMap { now =>
        val delta = now - initial

        metrics.recordClockDrift(delta - cpuStarvationCheckInterval) >>
          IO.realTime
            .flatMap(fd =>
              (onCpuStarvationWarn(
                CpuStarvationWarningMetrics(
                  fd,
                  delta - cpuStarvationCheckInterval,
                  cpuStarvationCheckThreshold,
                  cpuStarvationCheckInterval)) *> metrics.incCpuStarvationCount)
                .whenA(delta >= threshold)) >> go(now)
      }

    IO.monotonic.flatMap(go(_)).delayBy(cpuStarvationCheckInitialDelay)
  }

  def logWarning(cpuStarvationWarningMetrics: CpuStarvationWarningMetrics): IO[Unit] =
    Console
      .make[IO]
      .errorln(
        mkWarning(
          cpuStarvationWarningMetrics.starvationInterval * cpuStarvationWarningMetrics.starvationThreshold)(
          cpuStarvationWarningMetrics.occurrenceTime))

  private[this] def mkWarning(threshold: Duration)(when: FiniteDuration) =
    s"""|${format(when)} [WARNING] Your app's responsiveness to a new asynchronous
        | event (such as a new connection, an upstream response, or a timer) was in excess
        | of $threshold. Your CPU is probably starving. Consider increasing the
        | granularity of your delays or adding more cedes. This may also be a sign that you
        | are unintentionally running blocking I/O operations (such as File or InetAddress)
        | without the blocking combinator.""".stripMargin.replaceAll("\n", "")

}
