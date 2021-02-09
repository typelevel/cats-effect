/*
 * Copyright 2020-2021 Typelevel
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

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.atomic.AtomicReference
import cats.instances.tailRec
import scala.annotation.tailrec

class HashedWheelTimerScheduler(wheelSize: Int, resolution: FiniteDuration) extends Scheduler {

  def sleep(delay: FiniteDuration, task: Runnable): Runnable =
    if (delay.isFinite) {
      //The delay requested is less than the resolution we support
      //so run immediately
      if (delay < resolution) {
        task.run()
        noopCancel
      } else {
        val t = TaskState(task, delay)

        @tailrec
        def go(): Unit = {
          val op = pendingOps.get
          if (!pendingOps.compareAndSet(op, Register(t, op))) go()
        }

        go()
        cancelToken(t)
      }
    }
    // Delay is infinite so task is never run
    else noopCancel

  def nowMillis() = System.currentTimeMillis()

  def monotonicNanos() = System.nanoTime()

  def shutdown(): Unit = {
    canceled = true
  }

  private def cancelToken(task: TaskState): Runnable =
    () => {
      @tailrec
      def go(): Unit = {
        val op = pendingOps.get
        if (!pendingOps.compareAndSet(op, Cancel(task, op))) go()
      }
      go()
    }

  //TODO is this safe if resolution is < 1ms
  //Probably ok as Thread.sleep only allows millis
  //(or millis and nanos precision)
  private val res: Long = resolution.toMillis

  private val wheel: Array[Bucket] = (0.until(wheelSize)).map(_ => new Bucket()).toArray

  private val pendingOps: AtomicReference[Op] = new AtomicReference[Op](Noop)

  @volatile private var canceled = false

  private val thread = new Thread("io-timer") {
    def loop(previous: Long): Unit = {
      //TODO should we only check this every n iterations?
      if (canceled) {
        return
      }
      val start = System.currentTimeMillis()
      val ops = pendingOps.getAndSet(Noop)
      executeOps(ops)
      val end = System.currentTimeMillis()
      val diff = end - start
      if (diff < res) {
        Thread.sleep(res - diff)
      }
      loop(end)
    }

    loop(System.currentTimeMillis())
  }

  @tailrec
  private def executeOps(op: Op): Unit = op match {
    case Noop => ()
    //TODO insert into correct bucket
    case Register(state, next) => executeOps(next)
    case Cancel(state, next) => {
      state.canceled = true
      executeOps(next)
    }
  }

  private val noopCancel: Runnable = () => ()

  private case class TaskState(
      task: Runnable,
      delay: FiniteDuration,
      var next: TaskState = null,
      var canceled: Boolean = false)

  private class Bucket {

    var head: TaskState = null

    def add(state: TaskState): Unit = {
      state.next = head
      head = state
    }

  }

  private sealed trait Op
  private case class Register(state: TaskState, next: Op) extends Op
  private case class Cancel(state: TaskState, next: Op) extends Op
  private case object Noop extends Op

}
