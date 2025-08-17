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
 * An MBean interface for monitoring a single [[WorkerThread]] [[TimerHeap]].
 */
private[unsafe] trait TimerHeapSamplerMBean {

  /**
   * The current number of the outstanding timers, that remain to be executed.
   *
   * @note
   *   the value may differ between invocations
   */
  def getTimersOutstandingCount(): Int

  /**
   * The total number of the successfully executed timers.
   *
   * @note
   *   the value may differ between invocations
   */
  def getTotalTimersExecutedCount(): Long

  /**
   * The total number of the scheduled timers.
   *
   * @note
   *   the value may differ between invocations
   */
  def getTotalTimersScheduledCount(): Long

  /**
   * The total number of the canceled timers.
   *
   * @note
   *   the value may differ between invocations
   */
  def getTotalTimersCanceledCount(): Long

  /**
   * Returns the time in nanoseconds till the next due to fire.
   *
   * The negative number could indicate that the worker thread is overwhelmed by (long-running)
   * tasks and not able to check/trigger timers frequently enough. The indication is similar to
   * the starvation checker.
   *
   * Returns `None` when there is no upcoming timer.
   *
   * @note
   *   the value may differ between invocations
   */
  def getNextTimerDue(): Long

  /**
   * Returns the total number of times the heap packed itself to remove canceled timers.
   */
  def getPackCount(): Long

}
