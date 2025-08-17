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

package cats.effect.unsafe
package metrics

/**
 * An implementation of the [[WorkerThreadSamplerMBean]] interface which simply delegates to the
 * corresponding methods of the [[cats.effect.unsafe.WorkerThread]] being monitored.
 *
 * @param thread
 *   the monitored thread
 */
private[unsafe] final class WorkerThreadSampler(thread: WorkerThread.Metrics)
    extends WorkerThreadSamplerMBean {
  def getIdleTime(): Long = thread.getIdleTime()
  def getParkedCount(): Long = thread.getParkedCount()
  def getPolledCount(): Long = thread.getPolledCount()
  def getBlockingCount(): Long = thread.getBlockingCount()
  def getRespawnCount(): Long = thread.getRespawnCount()
}
