package cats.effect.metrics

import java.util.concurrent.atomic.AtomicLong

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

private[metrics] class CpuStarvationMbeanImpl private (
    counter: AtomicLong,
    currentClockDrift: AtomicLong,
    maxClockDrift: AtomicLong)
    extends CpuStarvationMbean {
  override def getCpuStarvationCount(): Long = counter.get()

  override def getMaxClockDriftMs(): Long = maxClockDrift.get()

  override def getCurrentClockDriftMs(): Long = currentClockDrift.get()

  def incStarvationCount: IO[Unit] = IO.delay(counter.incrementAndGet()).void

  def recordDrift(drift: FiniteDuration): IO[Unit] = {
    lazy val driftMs = drift.toMillis

    val maxDrift =
      if (driftMs > 0) IO.delay(maxClockDrift.updateAndGet(_.max(drift.toMillis))).void
      else IO.unit

    IO.delay(currentClockDrift.set(driftMs)) >> maxDrift
  }

}

object CpuStarvationMbeanImpl {
  private[metrics] def apply(): IO[CpuStarvationMbeanImpl] = for {
    counter <- IO.delay(new AtomicLong(0))
    currentClockDrift <- IO.delay(new AtomicLong(0))
    maxClockDrift <- IO.delay(new AtomicLong(0))
  } yield new CpuStarvationMbeanImpl(counter, currentClockDrift, maxClockDrift)
}
