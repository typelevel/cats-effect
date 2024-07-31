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

package cats.effect.unsafe
package metrics

import scala.concurrent.ExecutionContext

sealed trait WorkStealingPoolMetrics {
  def hash: String

  def compute: WorkStealingPoolMetrics.ComputeMetrics

  def localQueues: List[WorkStealingPoolMetrics.LocalQueueMetrics]
}

object WorkStealingPoolMetrics {

  sealed trait ComputeMetrics {
    def workerThreadCount(): Int
    def activeThreadCount(): Int
    def searchingThreadCount(): Int
    def blockedWorkerThreadCount(): Int
    def localQueueFiberCount(): Long
    def suspendedFiberCount(): Long
  }

  sealed trait LocalQueueMetrics {
    def index: Int
    def fiberCount(): Int
    def headIndex(): Int
    def tailIndex(): Int
    def totalFiberCount(): Long
    def totalSpilloverCount(): Long
    def successfulStealAttemptCount(): Long
    def stolenFiberCount(): Long
    def realHeadTag(): Int
    def stealHeadTag(): Int
    def tailTag(): Int
  }

  private[metrics] def apply(ec: ExecutionContext): Option[WorkStealingPoolMetrics] =
    ec match {
      case wstp: WorkStealingThreadPool[_] =>
        val metrics = new WorkStealingPoolMetrics {
          val hash: String =
            System.identityHashCode(wstp).toHexString

          val compute: ComputeMetrics =
            computeMetrics(wstp)

          val localQueues: List[LocalQueueMetrics] =
            wstp.localQueues.toList.zipWithIndex.map {
              case (queue, idx) => localQueueMetrics(queue, idx)
            }
        }

        Some(metrics)

      case _ =>
        None
    }

  private def computeMetrics(wstp: WorkStealingThreadPool[_]): ComputeMetrics =
    new ComputeMetrics {
      def workerThreadCount(): Int = wstp.getWorkerThreadCount()
      def activeThreadCount(): Int = wstp.getActiveThreadCount()
      def searchingThreadCount(): Int = wstp.getSearchingThreadCount()
      def blockedWorkerThreadCount(): Int = wstp.getBlockedWorkerThreadCount()
      def localQueueFiberCount(): Long = wstp.getLocalQueueFiberCount()
      def suspendedFiberCount(): Long = wstp.getSuspendedFiberCount()
    }

  private def localQueueMetrics(queue: LocalQueue, idx: Int): LocalQueueMetrics =
    new LocalQueueMetrics {
      def index: Int = idx
      def fiberCount(): Int = queue.getFiberCount()
      def headIndex(): Int = queue.getHeadIndex()
      def tailIndex(): Int = queue.getTailIndex()
      def totalFiberCount(): Long = queue.getTotalFiberCount()
      def totalSpilloverCount(): Long = queue.getTotalSpilloverCount()
      def successfulStealAttemptCount(): Long = queue.getSuccessfulStealAttemptCount()
      def stolenFiberCount(): Long = queue.getStolenFiberCount()
      def realHeadTag(): Int = queue.getRealHeadTag()
      def stealHeadTag(): Int = queue.getStealHeadTag()
      def tailTag(): Int = queue.getTailTag()
    }
}
