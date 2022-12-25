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

import scala.concurrent.ExecutionContext

import java.util.concurrent.locks.LockSupport

object SleepSystem extends PollingSystem {

  final class Poller private[SleepSystem] ()
  final class PollData private[SleepSystem] ()

  def makePoller(ec: ExecutionContext, data: () => PollData): Poller = new Poller

  def makePollData(): PollData = new PollData

  def closePollData(data: PollData): Unit = ()

  def poll(data: PollData, nanos: Long, reportFailure: Throwable => Unit): Boolean = {
    if (nanos < 0)
      LockSupport.park()
    else if (nanos > 0)
      LockSupport.parkNanos(nanos)
    else
      ()
    false
  }

  def interrupt(targetThread: Thread, targetData: PollData): Unit =
    LockSupport.unpark(targetThread)

}
