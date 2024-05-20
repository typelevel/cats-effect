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

package cats.effect.metrics

/**
 * An MBean interfaces for monitoring when CPU starvation occurs.
 */
private[metrics] trait CpuStarvationMBean {

  /**
   * Returns the number of times CPU starvation has occurred.
   *
   * @return
   *   count of the number of times CPU starvation has occurred.
   */
  def getCpuStarvationCount(): Long

  /**
   * Tracks the maximum clock seen during runtime.
   *
   * @return
   *   the current maximum clock drift observed in milliseconds.
   */
  def getMaxClockDriftMs(): Long

  /**
   * Tracks the current clock drift.
   *
   * @return
   *   the current clock drift in milliseconds.
   */
  def getCurrentClockDriftMs(): Long
}
