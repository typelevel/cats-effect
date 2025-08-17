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
 * An implementation of the [[TimerHeapSamplerMBean]] interface which simply delegates to the
 * corresponding methods of the [[cats.effect.unsafe.TimerHeap]] being monitored.
 *
 * @param timer
 *   the monitored timer heap
 */
private[unsafe] final class TimerHeapSampler(timer: TimerHeap) extends TimerHeapSamplerMBean {
  def getTimersOutstandingCount(): Int = timer.outstandingTimers()
  def getTotalTimersExecutedCount(): Long = timer.totalTimersExecuted()
  def getTotalTimersScheduledCount(): Long = timer.totalTimersScheduled()
  def getTotalTimersCanceledCount(): Long = timer.totalTimersCanceled()
  def getNextTimerDue(): Long = timer.nextTimerDue().getOrElse(0L)
  def getPackCount(): Long = timer.packCount()
}
