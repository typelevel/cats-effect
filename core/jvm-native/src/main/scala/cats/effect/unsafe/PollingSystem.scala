/*
 * Copyright 2020-2023 Typelevel
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

package cats.effect
package unsafe

abstract class PollingSystem {

  /**
   * The user-facing interface.
   */
  type Api <: AnyRef

  /**
   * The thread-local data structure used for polling.
   */
  type Poller <: AnyRef

  def close(): Unit

  def makeApi(register: (Poller => Unit) => Unit): Api

  def makePoller(): Poller

  def closePoller(poller: Poller): Unit

  /**
   * @param nanos
   *   the maximum duration for which to block, where `nanos == -1` indicates to block
   *   indefinitely.
   *
   * @return
   *   whether any events were polled
   */
  def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean

  /**
   * @return
   *   whether poll should be called again (i.e., there are more events to be polled)
   */
  def needsPoll(poller: Poller): Boolean

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit

}

private object PollingSystem {
  type WithPoller[P] = PollingSystem {
    type Poller = P
  }
}
