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

object SleepSystem extends PollingSystem {

  type Api = AnyRef
  type Poller = AnyRef

  def close(): Unit = ()

  def makeApi(access: (Poller => Unit) => Unit): Api = this

  def makePoller(): Poller = this

  def closePoller(poller: Poller): Unit = ()

  def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean = {
    if (nanos > 0)
      Thread.sleep(nanos / 1000000, (nanos % 1000000).toInt)
    false
  }

  def needsPoll(poller: Poller): Boolean = false

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ()

}
