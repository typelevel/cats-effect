/*
 * Copyright 2020-2021 Typelevel
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

/**
 * An MBean interface for monitoring the [[IO]] work stealing compute pool.
 */
trait ComputePoolSamplerMBean {

  /**
   * Returns the number of worker threads in the compute pool.
   *
   * @return the number of worker threads in the compute pool
   */
  def getWorkerThreadCount: Int

  /**
   * Returns the number of active worker threads currently executing fibers.
   *
   * @return the number of currently active worker threads
   */
  def getActiveThreadCount: Int

  /**
   * Returns the number of worker threads searching for fibers to steal from
   * other worker threads.
   *
   * @return the number of worker threads searching for work
   */
  def getSearchingThreadCount: Int

  /**
   * Returns the number of currently active helper threads substituting for
   * worker threads currently executing blocking actions.
   *
   * @return the number of currently active helper threads
   */
  def getActiveHelperThreadCount: Int

  /**
   * Returns the number of fibers enqueued on the overflow queue. This queue
   * also accepts fibers scheduled for execution by external threads.
   *
   * @return the number of fibers enqueued on the overflow queue
   */
  def getOverflowQueueFiberCount: Int

  /**
   * Returns the number of fibers enqueued on the batched queue. This queue
   * holds batches of fibers for more efficient transfer between worker threads.
   * Each batch contains `129` fibers.
   *
   * @return the number of fibers enqueued on the batched queue
   */
  def getBatchedQueueFiberCount: Int

  /**
   * Returns the number of fibers enqueued in the local queues of the worker
   * threads. This is an aggregate number consisting of metrics from all
   * worker threads.
   *
   * @return the number of fibers enqueued in the local queues
   */
  def getLocalQueueFiberCount: Int

  /**
   * Returns the number of suspended fibers (fibers whose asynchronous callback
   * has not been executed yet. Includes fibers executing `IO.async` or
   * `IO#sleep` calls.
   *
   * @return the number of suspended fibers
   */
  def getSuspendedFiberCount: Int
}
