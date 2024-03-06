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

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

@deprecated("Use default runtime with a custom PollingSystem", "3.6.0")
abstract class PollingExecutorScheduler(pollEvery: Int)
    extends ExecutionContextExecutor
    with Scheduler { outer =>

  private[this] val loop = new EventLoopExecutorScheduler(
    pollEvery,
    new PollingSystem {
      type Api = outer.type
      type Poller = outer.type
      private[this] var needsPoll = true
      def close(): Unit = ()
      def makeApi(access: (Poller => Unit) => Unit): Api = outer
      def makePoller(): Poller = outer
      def closePoller(poller: Poller): Unit = ()
      def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean = {
        needsPoll =
          if (nanos == -1)
            poller.poll(Duration.Inf)
          else
            poller.poll(nanos.nanos)
        true
      }
      def needsPoll(poller: Poller) = needsPoll
      def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ()
    }
  )

  final def execute(runnable: Runnable): Unit =
    loop.execute(runnable)

  final def sleep(delay: FiniteDuration, task: Runnable): Runnable =
    loop.sleep(delay, task)

  def reportFailure(t: Throwable): Unit = loop.reportFailure(t)

  def nowMillis() = loop.nowMillis()

  override def nowMicros(): Long = loop.nowMicros()

  def monotonicNanos() = loop.monotonicNanos()

  /**
   * @param timeout
   *   the maximum duration for which to block. ''However'', if `timeout == Inf` and there are
   *   no remaining events to poll for, this method should return `false` immediately. This is
   *   unfortunate but necessary so that this `ExecutionContext` can yield to the Scala Native
   *   global `ExecutionContext` which is currently hard-coded into every test framework,
   *   including JUnit, MUnit, and specs2.
   *
   * @return
   *   whether poll should be called again (i.e., there are more events to be polled)
   */
  protected def poll(timeout: Duration): Boolean

}
