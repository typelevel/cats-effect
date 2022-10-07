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

import cats.effect.std.Console
import cats.effect.unsafe.IORuntimeConfig
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

private[effect] object CpuStarvationCheck {

  def run(runtimeConfig: IORuntimeConfig): IO[Nothing] = {
    def go(initial: FiniteDuration): IO[Nothing] =
      IO.sleep(runtimeConfig.cpuStarvationCheckInterval) >> IO.monotonic.flatMap { now =>
        val delta = now - initial
        Console[IO]
          .errorln(warning)
          .whenA(delta >=
            runtimeConfig.cpuStarvationCheckInterval * (1 + runtimeConfig.cpuStarvationCheckThreshold)) >>
          go(now)
      }

    IO.monotonic.flatMap(go(_)).delayBy(runtimeConfig.cpuStarvationCheckInitialDelay)
  }

  private[this] val warning =
    """[WARNING] Your CPU is probably starving. Consider increasing the granularity of your `delay`s or adding more `cede`s. This may also be a sign that you are unintentionally running blocking I/O operations (such as File or InetAddress) without the `blocking` combinator."""

}
