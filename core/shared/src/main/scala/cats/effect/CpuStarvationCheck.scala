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

private[effect] object CpuStarvationCheck {
  def run(runtimeConfig: IORuntimeConfig): IO[Unit] =
    IO.monotonic
      .flatMap { now =>
        IO.sleep(runtimeConfig.cpuStarvationCheckInterval) >> IO
          .monotonic
          .map(_ - now)
          .flatMap { delta =>
            Console[IO]
              .errorln("[WARNING] you're CPU threadpool is probably starving")
              .whenA(delta > runtimeConfig.cpuStarvationCheckThreshold)
          }
      }
      .foreverM
      .delayBy(runtimeConfig.cpuStarvationCheckInitialDelay)
}
