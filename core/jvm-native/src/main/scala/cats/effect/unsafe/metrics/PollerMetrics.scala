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

trait PollerMetrics {

  /**
   * The current number of outstanding I/O operations.
   */
  def operationsOutstandingCount(): Int

  /**
   * The total number of I/O operations submitted.
   */
  def totalOperationsSubmittedCount(): Long

  /**
   * The total number of I/O operations succeeded.
   */
  def totalOperationsSucceededCount(): Long

  /**
   * The total number of I/O operations errored.
   */
  def totalOperationsErroredCount(): Long

  /**
   * The total number of I/O operations canceled.
   */
  def totalOperationsCanceledCount(): Long

  /**
   * The current number of outstanding accept operations.
   */
  def acceptOperationsOutstandingCount(): Int

  /**
   * The total number of accept operations submitted.
   */
  def totalAcceptOperationsSubmittedCount(): Long

  /**
   * The total number of accept operations succeeded.
   */
  def totalAcceptOperationsSucceededCount(): Long

  /**
   * The total number of accept operations errored.
   */
  def totalAcceptOperationsErroredCount(): Long

  /**
   * The total number of accept operations canceled.
   */
  def totalAcceptOperationsCanceledCount(): Long

  /**
   * The current number of outstanding connect operations.
   */
  def connectOperationsOutstandingCount(): Int

  /**
   * The total number of connect operations submitted.
   */
  def totalConnectOperationsSubmittedCount(): Long

  /**
   * The total number of connect operations succeeded.
   */
  def totalConnectOperationsSucceededCount(): Long

  /**
   * The total number of connect operations errored.
   */
  def totalConnectOperationsErroredCount(): Long

  /**
   * The total number of connect operations canceled.
   */
  def totalConnectOperationsCanceledCount(): Long

  /**
   * The current number of outstanding read operations.
   */
  def readOperationsOutstandingCount(): Int

  /**
   * The total number of read operations submitted.
   */
  def totalReadOperationsSubmittedCount(): Long

  /**
   * The total number of read operations succeeded.
   */
  def totalReadOperationsSucceededCount(): Long

  /**
   * The total number of read operations errored.
   */
  def totalReadOperationsErroredCount(): Long

  /**
   * The total number of read operations canceled.
   */
  def totalReadOperationsCanceledCount(): Long

  /**
   * The current number of outstanding write operations.
   */
  def writeOperationsOutstandingCount(): Int

  /**
   * The total number of write operations submitted.
   */
  def totalWriteOperationsSubmittedCount(): Long

  /**
   * The total number of write operations succeeded.
   */
  def totalWriteOperationsSucceededCount(): Long

  /**
   * The total number of write operations errored.
   */
  def totalWriteOperationsErroredCount(): Long

  /**
   * The total number of write operations canceled.
   */
  def totalWriteOperationsCanceledCount(): Long

}

object PollerMetrics {
  private[effect] object noop extends PollerMetrics {
    def operationsOutstandingCount(): Int = 0
    def totalOperationsSubmittedCount(): Long = 0
    def totalOperationsSucceededCount(): Long = 0
    def totalOperationsErroredCount(): Long = 0
    def totalOperationsCanceledCount(): Long = 0
    def acceptOperationsOutstandingCount(): Int = 0
    def totalAcceptOperationsSubmittedCount(): Long = 0
    def totalAcceptOperationsSucceededCount(): Long = 0
    def totalAcceptOperationsErroredCount(): Long = 0
    def totalAcceptOperationsCanceledCount(): Long = 0
    def connectOperationsOutstandingCount(): Int = 0
    def totalConnectOperationsSubmittedCount(): Long = 0
    def totalConnectOperationsSucceededCount(): Long = 0
    def totalConnectOperationsErroredCount(): Long = 0
    def totalConnectOperationsCanceledCount(): Long = 0
    def readOperationsOutstandingCount(): Int = 0
    def totalReadOperationsSubmittedCount(): Long = 0
    def totalReadOperationsSucceededCount(): Long = 0
    def totalReadOperationsErroredCount(): Long = 0
    def totalReadOperationsCanceledCount(): Long = 0
    def writeOperationsOutstandingCount(): Int = 0
    def totalWriteOperationsSubmittedCount(): Long = 0
    def totalWriteOperationsSucceededCount(): Long = 0
    def totalWriteOperationsErroredCount(): Long = 0
    def totalWriteOperationsCanceledCount(): Long = 0
    override def toString: String = "Noop"
  }
}
