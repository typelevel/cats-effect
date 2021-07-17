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
 * An implementation of the
 * [[cats.effect.unsafe.metrics.ComputePoolSamplerMBean]] for monitoring the
 * default [[cats.effect.unsafe.IORuntime]] compute pool backed by a
 * `cats.effect.unsafe.WorkStealingThreadPool`.
 */
final class ComputePoolSampler(pool: WorkStealingThreadPool) extends ComputePoolSamplerMBean {
  def getWorkerThreadCount: Int = pool.getWorkerThreadCount
  def getActiveThreadCount: Int = pool.getActiveThreadCount
  def getSearchingThreadCount: Int = pool.getSearchingThreadCount
  def getActiveHelperThreadCount: Int = pool.getActiveHelperThreadCount
  def getOverflowQueueFiberCount: Int = pool.getOverflowQueueFiberCount
  def getBatchedQueueFiberCount: Int = pool.getBatchedQueueFiberCount
  def getLocalQueueFiberCount: Int = pool.getLocalQueueFiberCount
  def getSuspendedFiberCount: Int = pool.getSuspendedFiberCount
  def getActiveFiberCount: Int = pool.getActiveFiberCount
  def getLifetimeFiberCount: Long = pool.getLifetimeFiberCount
}
