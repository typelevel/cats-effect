/*
 * Copyright 2020-2022 Typelevel
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

  type Poller

  def makePoller(): Poller

  def close(poller: Poller): Unit

  /**
   * @param nanos
   *   the maximum duration for which to block, where `nanos == -1` indicates to block
   *   indefinitely. ''However'', if `nanos == -1` and there are no remaining events to poll
   *   for, this method should return `false` immediately. This is unfortunate but necessary so
   *   that the `EventLoop` can yield to the Scala Native global `ExecutionContext` which is
   *   currently hard-coded into every test framework, including MUnit, specs2, and Weaver.
   *
   * @return
   *   whether poll should be called again (i.e., there are more events to be polled)
   */
  def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean

}
