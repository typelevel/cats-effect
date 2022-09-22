package cats.effect

import cats.syntax.all._
import unsafe.IORuntimeConfig
import cats.effect.std.Console

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
