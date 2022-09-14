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

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.scalanative.libc.errno
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.unsafe._
import scala.util.control.NonFatal

import java.util.{ArrayDeque, PriorityQueue}

abstract class PollingExecutorScheduler(pollEvery: Int)
    extends ExecutionContextExecutor
    with Scheduler {

  private[this] var needsReschedule: Boolean = true

  private[this] val executeQueue: ArrayDeque[Runnable] = new ArrayDeque
  private[this] val sleepQueue: PriorityQueue[SleepTask] = new PriorityQueue

  private[this] val noop: Runnable = () => ()

  private[this] def scheduleIfNeeded(): Unit = if (needsReschedule) {
    ExecutionContext.global.execute(() => loop())
    needsReschedule = false
  }

  final def execute(runnable: Runnable): Unit = {
    scheduleIfNeeded()
    executeQueue.addLast(runnable)
  }

  final def sleep(delay: FiniteDuration, task: Runnable): Runnable =
    if (delay <= Duration.Zero) {
      execute(task)
      noop
    } else {
      scheduleIfNeeded()
      val now = monotonicNanos()
      val sleepTask = new SleepTask(now + delay.toNanos, task)
      sleepQueue.offer(sleepTask)
      sleepTask
    }

  def reportFailure(t: Throwable): Unit = t.printStackTrace()

  def nowMillis() = System.currentTimeMillis()

  override def nowMicros(): Long =
    if (LinktimeInfo.isFreeBSD || LinktimeInfo.isLinux || LinktimeInfo.isMac) {
      import scala.scalanative.posix.time._
      import scala.scalanative.posix.timeOps._
      val ts = stackalloc[timespec]()
      if (clock_gettime(CLOCK_REALTIME, ts) != 0)
        throw new RuntimeException(s"clock_gettime: ${errno.errno}")
      ts.tv_sec * 1000000 + ts.tv_nsec / 1000
    } else {
      super.nowMicros()
    }

  def monotonicNanos() = System.nanoTime()

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

  private[this] def loop(): Unit = {
    needsReschedule = false

    var continue = true

    while (continue) {
      // execute the timers
      val now = monotonicNanos()
      while (!sleepQueue.isEmpty() && sleepQueue.peek().at <= now) {
        val task = sleepQueue.poll()
        try task.runnable.run()
        catch {
          case NonFatal(t) => reportFailure(t)
          case t: Throwable => IOFiber.onFatalFailure(t)
        }
      }

      // do up to pollEvery tasks
      var i = 0
      while (i < pollEvery && !executeQueue.isEmpty()) {
        val runnable = executeQueue.poll()
        try runnable.run()
        catch {
          case NonFatal(t) => reportFailure(t)
          case t: Throwable => IOFiber.onFatalFailure(t)
        }
        i += 1
      }

      // finally we poll
      val timeout =
        if (!executeQueue.isEmpty())
          Duration.Zero
        else if (!sleepQueue.isEmpty())
          Math.max(sleepQueue.peek().at - monotonicNanos(), 0).nanos
        else
          Duration.Inf

      val needsPoll = poll(timeout)

      continue = needsPoll || !executeQueue.isEmpty() || !sleepQueue.isEmpty()
    }

    needsReschedule = true
  }

  private[this] final class SleepTask(
      val at: Long,
      val runnable: Runnable
  ) extends Runnable
      with Comparable[SleepTask] {

    def run(): Unit = {
      sleepQueue.remove(this)
      ()
    }

    def compareTo(that: SleepTask): Int =
      java.lang.Long.compare(this.at, that.at)
  }

}
