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
 * An MBean interface for monitoring a [[WorkStealingThreadPool]] backed compute thread pool.
 */
private[unsafe] trait ComputePoolSamplerMBean {

  /**
   * Returns the number of [[WorkerThread]] instances backing the [[WorkStealingThreadPool]].
   *
   * @note
   *   This is a fixed value, as the [[WorkStealingThreadPool]] has a fixed number of worker
   *   threads.
   *
   * @return
   *   the number of worker threads backing the compute pool
   */
  def getWorkerThreadCount(): Int

  /**
   * Returns the number of active [[WorkerThread]] instances currently executing fibers on the
   * compute thread pool.
   *
   * @return
   *   the number of active worker threads
   */
  def getActiveThreadCount(): Int

  /**
   * Returns the number of [[WorkerThread]] instances currently searching for fibers to steal
   * from other worker threads.
   *
   * @return
   *   the number of worker threads searching for work
   */
  def getSearchingThreadCount(): Int

  /**
   * Returns the number of [[WorkerThread]] instances which are currently blocked due to running
   * blocking actions on the compute thread pool.
   *
   * @return
   *   the number of blocked worker threads
   */
  def getBlockedWorkerThreadCount(): Int

  /**
   * Returns the total number of fibers enqueued on all local queues.
   *
   * @return
   *   the total number of fibers enqueued on all local queues
   */
  def getLocalQueueFiberCount(): Long

  /**
   * Returns the number of fibers which are currently asynchronously suspended.
   *
   * @return
   *   the number of asynchronously suspended fibers
   */
  def getSuspendedFiberCount(): Long
}
