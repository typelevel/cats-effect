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
 * An MBean interface for monitoring a single [[WorkerThread]]
 * [[cats.effect.unsafe.PollingSystem.Poller]].
 */
private[unsafe] trait PollerSamplerMBean {

  /**
   * The current number of outstanding I/O operations.
   */
  def getOperationsOutstandingCount(): Int

  /**
   * The total number of I/O operations submitted.
   */
  def getTotalOperationsSubmittedCount(): Long

  /**
   * The total number of I/O operations succeeded.
   */
  def getTotalOperationsSucceededCount(): Long

  /**
   * The total number of I/O operations errored.
   */
  def getTotalOperationsErroredCount(): Long

  /**
   * The total number of I/O operations canceled.
   */
  def getTotalOperationsCanceledCount(): Long

  /**
   * The current number of outstanding accept operations.
   */
  def getAcceptOperationsOutstandingCount(): Int

  /**
   * The total number of accept operations submitted.
   */
  def getTotalAcceptOperationsSubmittedCount(): Long

  /**
   * The total number of accept operations succeeded.
   */
  def getTotalAcceptOperationsSucceededCount(): Long

  /**
   * The total number of accept operations errored.
   */
  def getTotalAcceptOperationsErroredCount(): Long

  /**
   * The total number of accept operations canceled.
   */
  def getTotalAcceptOperationsCanceledCount(): Long

  /**
   * The current number of outstanding connect operations.
   */
  def getConnectOperationsOutstandingCount(): Int

  /**
   * The total number of connect operations submitted.
   */
  def getTotalConnectOperationsSubmittedCount(): Long

  /**
   * The total number of connect operations succeeded.
   */
  def getTotalConnectOperationsSucceededCount(): Long

  /**
   * The total number of connect operations errored.
   */
  def getTotalConnectOperationsErroredCount(): Long

  /**
   * The total number of connect operations canceled.
   */
  def getTotalConnectOperationsCanceledCount(): Long

  /**
   * The current number of outstanding read operations.
   */
  def getReadOperationsOutstandingCount(): Int

  /**
   * The total number of read operations submitted.
   */
  def getTotalReadOperationsSubmittedCount(): Long

  /**
   * The total number of read operations succeeded.
   */
  def getTotalReadOperationsSucceededCount(): Long

  /**
   * The total number of read operations errored.
   */
  def getTotalReadOperationsErroredCount(): Long

  /**
   * The total number of read operations canceled.
   */
  def getTotalReadOperationsCanceledCount(): Long

  /**
   * The current number of outstanding write operations.
   */
  def getWriteOperationsOutstandingCount(): Int

  /**
   * The total number of write operations submitted.
   */
  def getTotalWriteOperationsSubmittedCount(): Long

  /**
   * The total number of write operations succeeded.
   */
  def getTotalWriteOperationsSucceededCount(): Long

  /**
   * The total number of write operations errored.
   */
  def getTotalWriteOperationsErroredCount(): Long

  /**
   * The total number of write operations canceled.
   */
  def getTotalWriteOperationsCanceledCount(): Long

}
