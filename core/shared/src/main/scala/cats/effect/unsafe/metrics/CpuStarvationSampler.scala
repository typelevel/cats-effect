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

package cats.effect.unsafe.metrics

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

import java.util.concurrent.atomic.AtomicLong

private[effect] trait CpuStarvationSampler extends CpuStarvationSamplerPlatform {

  /**
   * Returns the number of times CPU starvation has occurred.
   *
   * @return
   *   count of the number of times CPU starvation has occurred.
   */
  def cpuStarvationCount(): Long

  /**
   * Tracks the maximum clock seen during runtime.
   *
   * @return
   *   the current maximum clock drift observed in milliseconds.
   */
  def maxClockDriftMs(): Long

  /**
   * Tracks the current clock drift.
   *
   * @return
   *   the current clock drift in milliseconds.
   */
  def currentClockDriftMs(): Long

  def incCpuStarvationCount: IO[Unit]

  def recordClockDrift(drift: FiniteDuration): IO[Unit]
}

private[effect] object CpuStarvationSampler {

  private[effect] def create(): CpuStarvationSampler = {
    val counter = new AtomicLong(0)
    val maxClockDrift = new AtomicLong(0)
    val currentClockDrift = new AtomicLong(0)

    new CpuStarvationSampler {
      def cpuStarvationCount(): Long = counter.get
      def maxClockDriftMs(): Long = maxClockDrift.get
      def currentClockDriftMs(): Long = currentClockDrift.get()

      def incCpuStarvationCount: IO[Unit] =
        IO.delay {
          counter.incrementAndGet()
          ()
        }

      def recordClockDrift(drift: FiniteDuration): IO[Unit] = {
        val driftMs = drift.toMillis

        val maxDrift =
          if (driftMs > 0) IO.delay(maxClockDrift.updateAndGet(math.max(_, driftMs))).void
          else IO.unit

        IO.delay(currentClockDrift.set(driftMs)) >> maxDrift
      }
    }
  }

  private[effect] def noop: CpuStarvationSampler =
    new CpuStarvationSampler {
      def cpuStarvationCount(): Long = -1
      def maxClockDriftMs(): Long = -1
      def currentClockDriftMs(): Long = -1
      def incCpuStarvationCount: IO[Unit] = IO.unit
      def recordClockDrift(drift: FiniteDuration): IO[Unit] = IO.unit
    }

}
