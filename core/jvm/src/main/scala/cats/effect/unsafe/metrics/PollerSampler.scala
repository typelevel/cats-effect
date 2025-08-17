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

package cats.effect.unsafe.metrics

/**
 * An implementation of the [[PollerSamplerMBean]] interface which simply delegates to the
 * corresponding methods of the [[cats.effect.unsafe.PollingSystem.Poller]] being monitored.
 *
 * @param poller
 *   the monitored poller
 */
private[unsafe] final class PollerSampler(poller: PollerMetrics) extends PollerSamplerMBean {
  def getOperationsOutstandingCount(): Int = poller.operationsOutstandingCount()
  def getTotalOperationsSubmittedCount(): Long = poller.totalOperationsSubmittedCount()
  def getTotalOperationsSucceededCount(): Long = poller.totalOperationsSucceededCount()
  def getTotalOperationsErroredCount(): Long = poller.totalOperationsErroredCount()
  def getTotalOperationsCanceledCount(): Long = poller.totalOperationsCanceledCount()
  def getAcceptOperationsOutstandingCount(): Int = poller.acceptOperationsOutstandingCount()
  def getTotalAcceptOperationsSubmittedCount(): Long =
    poller.totalAcceptOperationsSubmittedCount()
  def getTotalAcceptOperationsSucceededCount(): Long =
    poller.totalAcceptOperationsSucceededCount()
  def getTotalAcceptOperationsErroredCount(): Long = poller.totalAcceptOperationsErroredCount()
  def getTotalAcceptOperationsCanceledCount(): Long =
    poller.totalAcceptOperationsCanceledCount()
  def getConnectOperationsOutstandingCount(): Int = poller.connectOperationsOutstandingCount()
  def getTotalConnectOperationsSubmittedCount(): Long =
    poller.totalConnectOperationsSubmittedCount()
  def getTotalConnectOperationsSucceededCount(): Long =
    poller.totalConnectOperationsSucceededCount()
  def getTotalConnectOperationsErroredCount(): Long =
    poller.totalConnectOperationsErroredCount()
  def getTotalConnectOperationsCanceledCount(): Long =
    poller.totalConnectOperationsCanceledCount()
  def getReadOperationsOutstandingCount(): Int = poller.readOperationsOutstandingCount()
  def getTotalReadOperationsSubmittedCount(): Long = poller.totalReadOperationsSubmittedCount()
  def getTotalReadOperationsSucceededCount(): Long = poller.totalReadOperationsSucceededCount()
  def getTotalReadOperationsErroredCount(): Long = poller.totalReadOperationsErroredCount()
  def getTotalReadOperationsCanceledCount(): Long = poller.totalReadOperationsCanceledCount()
  def getWriteOperationsOutstandingCount(): Int = poller.writeOperationsOutstandingCount()
  def getTotalWriteOperationsSubmittedCount(): Long =
    poller.totalWriteOperationsSubmittedCount()
  def getTotalWriteOperationsSucceededCount(): Long =
    poller.totalWriteOperationsSucceededCount()
  def getTotalWriteOperationsErroredCount(): Long = poller.totalWriteOperationsErroredCount()
  def getTotalWriteOperationsCanceledCount(): Long = poller.totalWriteOperationsCanceledCount()
}
