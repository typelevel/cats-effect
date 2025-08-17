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
 * An MBean interface for monitoring a single [[WorkerThread]].
 */
private[unsafe] trait WorkerThreadSamplerMBean {

  /**
   * The total amount of time in nanoseconds that this WorkerThread has been parked.
   */
  def getIdleTime(): Long

  /**
   * The total number of times that this WorkerThread has parked.
   */
  def getParkedCount(): Long

  /**
   * The total number of times that this WorkerThread has polled for I/O events.
   */
  def getPolledCount(): Long

  /**
   * The total number of times that this WorkerThread has switched to a blocking thread and been
   * replaced.
   */
  def getBlockingCount(): Long

  /**
   * The total number of times that this WorkerThread has been replaced by a newly spawned
   * thread.
   */
  def getRespawnCount(): Long

}
