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

object SleepSystem extends PollingSystem {

  final class GlobalPollingState private[SleepSystem] ()
  final class Poller private[SleepSystem] ()

  def makeGlobalPollingState(register: (Poller => Unit) => Unit): GlobalPollingState =
    new GlobalPollingState

  def makePoller(): Poller = new Poller

  def closePoller(poller: Poller): Unit = ()

  def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean = {
    if (nanos > 0)
      Thread.sleep(nanos / 1000000, (nanos % 1000000).toInt)
    false
  }

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ()

}
