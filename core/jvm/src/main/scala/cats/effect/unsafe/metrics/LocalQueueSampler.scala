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
 * An implementation of the [[LocalQueueSamplerMBean]] interface which simply delegates to the
 * corresponding methods of the [[cats.effect.unsafe.LocalQueue]] being monitored.
 *
 * @param queue
 *   the monitored local queue
 */
private[unsafe] final class LocalQueueSampler(queue: LocalQueue)
    extends LocalQueueSamplerMBean {
  def getFiberCount(): Int = queue.getFiberCount()
  def getHeadIndex(): Int = queue.getHeadIndex()
  def getTailIndex(): Int = queue.getTailIndex()
  def getTotalFiberCount(): Long = queue.getTotalFiberCount()
  def getTotalSpilloverCount(): Long = queue.getTotalSpilloverCount()
  def getSuccessfulStealAttemptCount(): Long = queue.getSuccessfulStealAttemptCount()
  def getStolenFiberCount(): Long = queue.getStolenFiberCount()
  def getRealHeadTag(): Int = queue.getRealHeadTag()
  def getStealHeadTag(): Int = queue.getStealHeadTag()
  def getTailTag(): Int = queue.getTailTag()
}
