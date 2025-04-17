/*
 * Copyright 2020-2025 Typelevel
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

import scala.concurrent.ExecutionContext

private[metrics] abstract class IORuntimeMetricsCompanionPlatform {
  this: IORuntimeMetrics.type =>

  private[unsafe] def apply(ec: ExecutionContext): IORuntimeMetrics =
    new IORuntimeMetrics {
      private[effect] val cpuStarvationSampler: CpuStarvationSampler =
        CpuStarvationSampler()

      val cpuStarvation: CpuStarvationMetrics =
        CpuStarvationMetrics(cpuStarvationSampler)

      val workStealingThreadPool: Option[WorkStealingThreadPoolMetrics] =
        WorkStealingThreadPoolMetrics(ec)
    }

}
