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

package cats.effect.unsafe.metrics

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

import java.util.concurrent.atomic.AtomicLong

private[effect] final class CpuStarvationSampler private (
    counter: AtomicLong,
    clockDriftCurrent: AtomicLong,
    clockDriftMax: AtomicLong
) {
  def cpuStarvationCount(): Long =
    counter.get()

  def clockDriftCurrentMs(): Long =
    clockDriftCurrent.get()

  def clockDriftMaxMs(): Long =
    clockDriftMax.get()

  def incCpuStarvationCount: IO[Unit] =
    IO.delay {
      counter.incrementAndGet()
      ()
    }

  def recordClockDrift(drift: FiniteDuration): IO[Unit] = {
    val driftMs = drift.toMillis

    val maxDrift =
      if (driftMs > 0) IO.delay {
        clockDriftMax.updateAndGet(math.max(_, driftMs))
        ()
      }
      else IO.unit

    IO.delay(clockDriftCurrent.set(driftMs)) >> maxDrift
  }

}

private[effect] object CpuStarvationSampler {

  private[effect] def apply(): CpuStarvationSampler =
    new CpuStarvationSampler(new AtomicLong(0), new AtomicLong(0), new AtomicLong(0))

}
