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

/**
 * An MBean interface for monitoring a single [[WorkerThread]] [[LocalQueue]].
 */
private[unsafe] trait LocalQueueSamplerMBean {

  /**
   * Returns the number of fibers enqueued on the monitored [[LocalQueue]].
   *
   * @return
   *   the number of fibers enqueued on the local queue
   */
  def getFiberCount(): Int

  /**
   * Returns the index into the circular buffer backing the monitored [[LocalQueue]] which
   * represents the head of the queue.
   *
   * @return
   *   the index representing the head of the queue
   */
  def getHeadIndex(): Int

  /**
   * Returns the index into the circular buffer backing the monitored [[LocalQueue]] which
   * represents the tail of the queue.
   *
   * @return
   *   the index representing the tail of the queue
   */
  def getTailIndex(): Int

  /**
   * Returns the total number of fibers enqueued on the monitored [[LocalQueue]] during its
   * lifetime.
   *
   * @return
   *   the total number of fibers enqueued during the lifetime of the local queue
   */
  def getTotalFiberCount(): Long

  /**
   * Returns the total number of fibers spilt over to the external queue during the lifetime of
   * the monitored [[LocalQueue]].
   *
   * @return
   *   the total number of fibers spilt over to the external queue
   */
  def getTotalSpilloverCount(): Long

  /**
   * Returns the total number of successful steal attempts by other worker threads from the
   * monitored [[LocalQueue]] during its lifetime.
   *
   * @return
   *   the total number of successful steal attempts by other worker threads
   */
  def getSuccessfulStealAttemptCount(): Long

  /**
   * Returns the total number of stolen fibers by other worker threads from the monitored
   * [[LocalQueue]] during its lifetime.
   *
   * @return
   *   the total number of stolen fibers by other worker threads
   */
  def getStolenFiberCount(): Long

  /**
   * Exposes the "real" value of the head of the monitored [[LocalQueue]]. This value represents
   * the state of the head which is valid for the owner worker thread. This is an unsigned 16
   * bit integer.
   *
   * @note
   *   For more details, consult the comments in the source code for
   *   [[cats.effect.unsafe.LocalQueue]].
   *
   * @return
   *   the "real" value of the head of the local queue
   */
  def getRealHeadTag(): Int

  /**
   * Exposes the "steal" tag of the head of the monitored [[LocalQueue]]. This value represents
   * the state of the head which is valid for any worker thread looking to steal work from this
   * local queue. This is an unsigned 16 bit integer.
   *
   * @note
   *   For more details, consult the comments in the source code for
   *   [[cats.effect.unsafe.LocalQueue]].
   *
   * @return
   *   the "steal" tag of the head of the local queue
   */
  def getStealHeadTag(): Int

  /**
   * Exposes the "tail" tag of the tail of the monitored [[LocalQueue]]. This value represents
   * the state of the tail which is valid for the owner worker thread, used for enqueuing fibers
   * into the local queue, as well as any other worker thread looking to steal work from this
   * local queue, used for calculating how many fibers to steal. This is an unsigned 16 bit
   * integer.
   *
   * @note
   *   For more details, consult the comments in the source code for
   *   [[cats.effect.unsafe.LocalQueue]].
   *
   * @return
   *   the "tail" tag of the tail of the local queue
   */
  def getTailTag(): Int
}
