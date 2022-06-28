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

import scala.collection.mutable.{ListBuffer, PriorityQueue}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.util.control.NonFatal

import java.time.Instant
import java.time.temporal.ChronoField

abstract class PollingExecutorScheduler extends ExecutionContextExecutor with Scheduler {

  import PollingExecutorScheduler._

  private[this] var state: State = State.Idle

  private[this] val executeQueue: ListBuffer[Runnable] = new ListBuffer
  private[this] val sleepQueue: PriorityQueue[ScheduledTask] =
    new PriorityQueue()(Ordering.by(-_.at))

  private[this] def scheduleIfNeeded(): Unit = if (state == State.Idle) {
    ExecutionContext.global.execute(() => loop())
    state = State.Scheduled
  }

  final def execute(runnable: Runnable): Unit = {
    scheduleIfNeeded()
    executeQueue += runnable
  }

  final def sleep(delay: FiniteDuration, task: Runnable): Runnable = {
    scheduleIfNeeded()
    val scheduledTask = new ScheduledTask(monotonicNanos() + delay.toNanos, task)
    sleepQueue += scheduledTask
    () =>
      scheduledTask.canceled = true // TODO this is a memory leak, better to remove completely
  }

  def reportFailure(t: Throwable): Unit = t.printStackTrace()

  def nowMillis() = System.currentTimeMillis()

  override def nowMicros(): Long = {
    val now = Instant.now()
    now.getEpochSecond * 1000000 + now.getLong(ChronoField.MICRO_OF_SECOND)
  }

  def monotonicNanos() = System.nanoTime()

  def poll(timeout: Duration): Unit

  private[this] def loop(): Unit = {
    state = State.Running

    while (executeQueue.nonEmpty || sleepQueue.nonEmpty) {

      if (executeQueue.nonEmpty) {
        val runnable = executeQueue.remove(0)
        try {
          runnable.run()
        } catch {
          case NonFatal(t) =>
            reportFailure(t)
        }
      }

      while (sleepQueue.nonEmpty && sleepQueue.head.canceled) {
        sleepQueue.dequeue()
      }

      if (sleepQueue.nonEmpty) {
        val now = monotonicNanos()
        val task = sleepQueue.head
        if (now >= task.at) {
          sleepQueue.dequeue()
          try {
            task.runnable.run()
          } catch {
            case NonFatal(t) =>
              reportFailure(t)
          }
        } else if (executeQueue.isEmpty) {
          val delta = task.at - now
          poll(delta.nanos)
        }

      }

    }

    state = State.Idle
  }

}

object PollingExecutorScheduler {

  private sealed abstract class State
  private object State {
    case object Idle extends State // nothing to do
    case object Scheduled extends State // scheduled on global EC
    case object Running extends State // running
  }

  private final class ScheduledTask(
      val at: Long,
      val runnable: Runnable,
      var canceled: Boolean = false
  )

}
