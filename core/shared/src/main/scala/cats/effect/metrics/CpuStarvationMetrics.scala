package cats.effect.metrics

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

private[effect] trait CpuStarvationMetrics {
  def incCpuStarvationCount: IO[Unit]

  def recordClockDrift(drift: FiniteDuration): IO[Unit]
}

object CpuStarvationMetrics {
  private[effect] def noOp: CpuStarvationMetrics = new CpuStarvationMetrics {
    override def incCpuStarvationCount: IO[Unit] = IO.unit

    override def recordClockDrift(drift: FiniteDuration): IO[Unit] = IO.unit
  }
}
