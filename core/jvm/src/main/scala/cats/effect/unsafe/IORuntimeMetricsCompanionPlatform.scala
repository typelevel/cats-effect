/*
 * Copyright 2020-2023 Typelevel
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

import scala.concurrent.ExecutionContext

private[unsafe] abstract class IORuntimeMetricsCompanionPlatform {
  this: IORuntimeMetrics.type =>

  sealed trait ComputeMetrics {
    def workerThreadCount(): Int
    def activeThreadCount(): Int
    def searchingThreadCount(): Int
    def blockedWorkerThreadCount(): Int
    def localQueueFiberCount(): Long
    def suspendedFiberCount(): Long
  }

  private[unsafe] def create(
      ec: ExecutionContext,
      starvation: CpuStarvationSampler
  ): IORuntimeMetrics =
    new IORuntimeMetrics {
      val cpuStarvation: Option[CPUStarvationMetrics] =
        Some(CPUStarvationMetrics.fromMetrics(starvation))

      val compute: Option[ComputeMetrics] =
        computeMetrics(ec)
    }

  private[unsafe] def noop: IORuntimeMetrics =
    new IORuntimeMetrics {
      val compute: Option[ComputeMetrics] = None
      val cpuStarvation: Option[CPUStarvationMetrics] = None
    }

  private def computeMetrics(compute: ExecutionContext): Option[ComputeMetrics] =
    compute match {
      case wstp: WorkStealingThreadPool[_] => Some(fromWSTP(wstp))
      case _ => None
    }

  private def fromWSTP(wstp: WorkStealingThreadPool[_]): ComputeMetrics =
    new ComputeMetrics {
      def workerThreadCount(): Int = wstp.getWorkerThreadCount()
      def activeThreadCount(): Int = wstp.getActiveThreadCount()
      def searchingThreadCount(): Int = wstp.getSearchingThreadCount()
      def blockedWorkerThreadCount(): Int = wstp.getBlockedWorkerThreadCount()
      def localQueueFiberCount(): Long = wstp.getLocalQueueFiberCount()
      def suspendedFiberCount(): Long = wstp.getSuspendedFiberCount()
    }

}
