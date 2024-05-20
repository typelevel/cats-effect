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

package cats.effect.metrics

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

import java.util.concurrent.atomic.AtomicLong

private[metrics] class CpuStarvation private (
    counter: AtomicLong,
    currentClockDrift: AtomicLong,
    maxClockDrift: AtomicLong)
    extends CpuStarvationMBean {

  override def getCpuStarvationCount(): Long = counter.get()

  override def getMaxClockDriftMs(): Long = maxClockDrift.get()

  override def getCurrentClockDriftMs(): Long = currentClockDrift.get()

  def incStarvationCount: IO[Unit] = IO.delay(counter.incrementAndGet()).void

  def recordDrift(drift: FiniteDuration): IO[Unit] = {
    val driftMs = drift.toMillis

    val maxDrift =
      if (driftMs > 0) IO.delay(maxClockDrift.updateAndGet(math.max(_, driftMs))).void
      else IO.unit

    IO.delay(currentClockDrift.set(driftMs)) >> maxDrift
  }

}

private[metrics] object CpuStarvation {
  private[metrics] def apply(): IO[CpuStarvation] = for {
    counter <- IO.delay(new AtomicLong(0))
    currentClockDrift <- IO.delay(new AtomicLong(0))
    maxClockDrift <- IO.delay(new AtomicLong(0))
  } yield new CpuStarvation(counter, currentClockDrift, maxClockDrift)
}
