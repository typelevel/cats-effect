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

/**
 * An implementation of the [[ComputePoolSamplerMBean]] interface which simply delegates to the
 * corresponding methods of the [[cats.effect.unsafe.WorkStealingThreadPool]] being monitored.
 *
 * @param compute
 *   the monitored compute work stealing thread pool
 */
private[unsafe] final class ComputePoolSampler(compute: WorkStealingThreadPool[_])
    extends ComputePoolSamplerMBean {
  def getWorkerThreadCount(): Int = compute.getWorkerThreadCount()
  def getActiveThreadCount(): Int = compute.getActiveThreadCount()
  def getSearchingThreadCount(): Int = compute.getSearchingThreadCount()
  def getBlockedWorkerThreadCount(): Int = compute.getBlockedWorkerThreadCount()
  def getLocalQueueFiberCount(): Long = compute.getLocalQueueFiberCount()
  def getSuspendedFiberCount(): Long = compute.getSuspendedFiberCount()
}
