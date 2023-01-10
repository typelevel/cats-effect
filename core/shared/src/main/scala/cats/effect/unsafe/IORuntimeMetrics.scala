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

package cats.effect.unsafe

import cats.effect.unsafe.metrics.CpuStarvationSampler

trait IORuntimeMetrics extends IORuntimeMetricsPlatform {
  def cpuStarvation: Option[IORuntimeMetrics.CPUStarvationMetrics]
}

object IORuntimeMetrics extends IORuntimeMetricsCompanionPlatform {

  sealed trait CPUStarvationMetrics {
    def starvationCount(): Long
    def maxClockDriftMs(): Long
    def currentClockDriftMs(): Long
  }

  private[effect] object CPUStarvationMetrics {
    def fromMetrics(metrics: CpuStarvationSampler): CPUStarvationMetrics =
      new CPUStarvationMetrics {
        def starvationCount(): Long = metrics.cpuStarvationCount()
        def maxClockDriftMs(): Long = metrics.maxClockDriftMs()
        def currentClockDriftMs(): Long = metrics.currentClockDriftMs()
      }

    def noop: CPUStarvationMetrics =
      new CPUStarvationMetrics {
        def starvationCount(): Long = -1
        def maxClockDriftMs(): Long = -1
        def currentClockDriftMs(): Long = -1
      }
  }

}
