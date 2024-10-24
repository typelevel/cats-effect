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

package cats.effect
package unsafe

/**
 * Represents a stateful system for managing and interacting with a polling system. Polling
 * systems are typically used in scenarios such as handling multiplexed blocking I/O or other
 * event-driven systems, where one needs to repeatedly check (or "poll") some condition or
 * state, blocking up to some timeout until it is ready.
 *
 * This class abstracts the general components and actions of a polling system, such as:
 *   - The user-facing interface (API) which interacts with the outside world
 *   - The thread-local data structure used for polling, which keeps track of the internal state
 *     of the system and its events
 *   - The lifecycle management methods, such as creating and closing the polling system and its
 *     components
 *   - The runtime interaction methods, such as polling events and interrupting the process
 */
abstract class PollingSystem {

  /**
   * The user-facing interface.
   */
  type Api <: AnyRef

  /**
   * The thread-local data structure used for polling.
   */
  type Poller <: AnyRef

  /**
   * Closes the polling system.
   */
  def close(): Unit

  /**
   * Creates a new instance of the user-facing interface.
   */
  def makeApi(provider: PollerProvider[Poller]): Api

  /**
   * Creates a new instance of the thread-local data structure used for polling.
   *
   * @return
   *   an instance of the poller `Poller`.
   */
  def makePoller(): Poller

  /**
   * Closes a specific poller.
   *
   * @param poller
   *   the poller to be closed.
   */
  def closePoller(poller: Poller): Unit

  /**
   * @param poller
   *   the thread-local [[Poller]] used to poll events.
   *
   * @param nanos
   *   the maximum duration for which to block, where `nanos == -1` indicates to block
   *   indefinitely.
   *
   * @param reportFailure
   *   callback that handles any failures that occur during polling.
   *
   * @return
   *   whether any events were polled. e.g. if the method returned due to timeout, this should
   *   be `false`.
   */
  def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean

  /**
   * @return
   *   whether poll should be called again (i.e., there are more events to be polled)
   */
  def needsPoll(poller: Poller): Boolean

  /**
   * Interrupts a specific target poller running on a specific target thread.
   *
   * @param targetThread
   *   is the thread where the target poller is running.
   * @param targetPoller
   *   is the poller to be interrupted.
   */
  def interrupt(targetThread: Thread, targetPoller: Poller): Unit

}

trait PollerProvider[P] {

  /**
   * Register a callback to obtain a thread-local `Poller`
   */
  def accessPoller(cb: P => Unit): Unit

  /**
   * Returns `true` if it is safe to interact with this `Poller`. Implementors of this method
   * may be best-effort: it is always safe to return `false`, so callers must have an adequate
   * fallback for the non-owning case.
   */
  def ownPoller(poller: P): Boolean
}

object PollingSystem {

  /**
   * Type alias for a `PollingSystem` that has a specified `Poller` type.
   *
   * @tparam P
   *   The type of the `Poller` in the `PollingSystem`.
   */
  type WithPoller[P] = PollingSystem {
    type Poller = P
  }
}
