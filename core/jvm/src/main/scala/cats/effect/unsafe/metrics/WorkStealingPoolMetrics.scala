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

/**
 * Represents metrics associated with a work-stealing thread pool.
 */
sealed trait WorkStealingPoolMetrics {

  /**
   * The hash code of the instrumented work-stealing thread pool. This hash uniquely identifies
   * the specific thread pool.
   */
  def hash: String

  /**
   * Compute-specific metrics of the work-stealing thread pool.
   */
  def compute: WorkStealingPoolMetrics.ComputeMetrics

  /**
   * The list of queue-specific metrics of the work-stealing thread pool.
   */
  def localQueues: List[WorkStealingPoolMetrics.LocalQueueMetrics]
}

object WorkStealingPoolMetrics {

  /**
   * The compute metrics of the work-stealing thread pool (WSTP).
   */
  sealed trait ComputeMetrics {

    /**
     * The number of worker thread instances backing the work-stealing thread pool (WSTP).
     *
     * @note
     *   this is a fixed value, as the WSTP has a fixed number of worker threads.
     */
    def workerThreadCount(): Int

    /**
     * The number of active worker thread instances currently executing fibers on the compute
     * thread pool.
     *
     * @note
     *   the value may differ between invocations
     */
    def activeThreadCount(): Int

    /**
     * The number of worker thread instances currently searching for fibers to steal from other
     * worker threads.
     *
     * @note
     *   the value may differ between invocations
     */
    def searchingThreadCount(): Int

    /**
     * The number of worker thread instances that can run blocking actions on the compute thread
     * pool.
     *
     * @note
     *   the value may differ between invocations
     */
    def blockedWorkerThreadCount(): Int

    /**
     * The total number of fibers enqueued on all local queues.
     *
     * @note
     *   the value may differ between invocations
     */
    def localQueueFiberCount(): Long

    /**
     * The number of fibers which are currently asynchronously suspended.
     *
     * @note
     *   This counter is not synchronized due to performance reasons and might be reporting
     *   out-of-date numbers.
     *
     * @note
     *   the value may differ between invocations
     */
    def suspendedFiberCount(): Long
  }

  /**
   * The metrics of the local queue.
   */
  sealed trait LocalQueueMetrics {

    /**
     * The index of the LocalQueue.
     */
    def index: Int

    /**
     * The current number of enqueued fibers.
     *
     * @note
     *   the value may differ between invocations
     */
    def fiberCount(): Int

    /**
     * The total number of fibers enqueued during the lifetime of the local queue.
     *
     * @note
     *   the value may differ between invocations
     */
    def totalFiberCount(): Long

    /**
     * The total number of fibers spilt over to the external queue.
     *
     * @note
     *   the value may differ between invocations
     */
    def totalSpilloverCount(): Long

    /**
     * The total number of successful steal attempts by other worker threads.
     *
     * @note
     *   the value may differ between invocations
     */
    def successfulStealAttemptCount(): Long

    /**
     * The total number of stolen fibers by other worker threads.
     *
     * @note
     *   the value may differ between invocations
     */
    def stolenFiberCount(): Long

    /**
     * The index that represents the head of the queue.
     *
     * @note
     *   the value may differ between invocations
     */
    def headIndex(): Int

    /**
     * The index that represents the tail of the queue.
     *
     * @note
     *   the value may differ between invocations
     */
    def tailIndex(): Int

    /**
     * The "real" value of the head of the local queue. This value represents the state of the
     * head which is valid for the owner worker thread. This is an unsigned 16 bit integer.
     *
     * @note
     *   the value may differ between invocations
     */
    def realHeadTag(): Int

    /**
     * The "steal" tag of the head of the local queue. This value represents the state of the
     * head which is valid for any worker thread looking to steal work from this local queue.
     * This is an unsigned 16 bit integer.
     *
     * @note
     *   the value may differ between invocations
     */
    def stealHeadTag(): Int

    /**
     * The "tail" tag of the tail of the local queue. This value represents the state of the
     * tail which is valid for the owner worker thread, used for enqueuing fibers into the local
     * queue, as well as any other worker thread looking to steal work from this local queue,
     * used for calculating how many fibers to steal. This is an unsigned 16 bit integer.
     *
     * @note
     *   the value may differ between invocations
     */
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
      def totalFiberCount(): Long = queue.getTotalFiberCount()
      def totalSpilloverCount(): Long = queue.getTotalSpilloverCount()
      def successfulStealAttemptCount(): Long = queue.getSuccessfulStealAttemptCount()
      def stolenFiberCount(): Long = queue.getStolenFiberCount()
      def headIndex(): Int = queue.getHeadIndex()
      def tailIndex(): Int = queue.getTailIndex()
      def realHeadTag(): Int = queue.getRealHeadTag()
      def stealHeadTag(): Int = queue.getStealHeadTag()
      def tailTag(): Int = queue.getTailTag()
    }
}
