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

package cats.effect.unsafe

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.util.control.NonFatal

import java.time.Instant
import java.time.temporal.ChronoField
import java.util.{ArrayDeque, PriorityQueue}

abstract class PollingExecutorScheduler extends ExecutionContextExecutor with Scheduler {

  import PollingExecutorScheduler._

  private[this] var needsReschedule: Boolean = true
  private[this] var inLoop: Boolean = false
  private[this] var cachedNow: Long = _

  private[this] var executeQueue: ArrayDeque[Runnable] = new ArrayDeque
  private[this] var cachedExecuteQueue: ArrayDeque[Runnable] = new ArrayDeque
  private[this] val sleepQueue: PriorityQueue[ScheduledTask] = new PriorityQueue

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
    if (delay == Duration.Zero) {
      execute(task)
      noop
    } else {
      scheduleIfNeeded()
      val now = if (inLoop) cachedNow else monotonicNanos()
      val scheduledTask = new ScheduledTask(now + delay.toNanos, task)
      sleepQueue.offer(scheduledTask)
      scheduledTask
    }

  def reportFailure(t: Throwable): Unit = t.printStackTrace()

  def nowMillis() = System.currentTimeMillis()

  override def nowMicros(): Long = {
    val now = Instant.now()
    now.getEpochSecond * 1000000 + now.getLong(ChronoField.MICRO_OF_SECOND)
  }

  def monotonicNanos() = System.nanoTime()

  /**
   * @return
   *   whether poll should be called again (i.e., there is more work queued)
   */
  def poll(timeout: Duration): Boolean

  private[this] def loop(): Unit = {
    needsReschedule = false
    inLoop = true

    var continue = true

    while (continue) {
      // cache the timestamp for this tick
      cachedNow = monotonicNanos()

      // swap the task queues
      val todo = executeQueue
      executeQueue = cachedExecuteQueue
      cachedExecuteQueue = todo

      // do all the tasks
      while (!todo.isEmpty()) {
        val runnable = todo.poll()
        try {
          runnable.run()
        } catch {
          case NonFatal(t) =>
            reportFailure(t)
        }
      }

      // execute the timers
      while (!sleepQueue.isEmpty() && sleepQueue.peek().canceled) {
        sleepQueue.poll()
      }

      while (!sleepQueue.isEmpty() && sleepQueue.peek().at <= cachedNow) {
        val task = sleepQueue.poll()
        try {
          task.runnable.run()
        } catch {
          case NonFatal(t) =>
            reportFailure(t)
        }
      }

      val sleepIter = sleepQueue.iterator()
      while (sleepIter.hasNext()) {
        if (sleepIter.next().canceled) sleepIter.remove()
      }

      // now we poll
      val timeout =
        if (!executeQueue.isEmpty())
          Duration.Zero
        else if (!sleepQueue.isEmpty())
          (sleepQueue.peek().at - cachedNow).nanos
        else
          Duration.Inf

      val needsPoll = poll(timeout)

      continue = needsPoll || !executeQueue.isEmpty() || !sleepQueue.isEmpty()
    }

    needsReschedule = true
    inLoop = false
  }

}

object PollingExecutorScheduler {

  private final class ScheduledTask(
      val at: Long,
      val runnable: Runnable,
      var canceled: Boolean = false
  ) extends Runnable
      with Comparable[ScheduledTask] {

    def run(): Unit = canceled = true

    def compareTo(that: ScheduledTask): Int =
      java.lang.Long.compare(this.at, that.at)

  }

}
