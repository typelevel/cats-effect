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
import scala.annotation.tailrec
import scala.util.control.NonFatal

/*
 * Hashed wheel timer based on George Varghese and Tony Lauck's paper <a
 * href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z"> 'Hashed and
 * Hierarchical Timing Wheels: data structures to efficiently implement a timer
 * facility' and the http4s implementation
 * https://github.com/http4s/blaze/blob/b9305ccb2e04ee62bde6619d7e7e2d17ea645e37/core/src/main/scala/org/http4s/blaze/util/TickWheelExecutor.scala
 */
class HashedWheelTimerScheduler(wheelSize: Int, resolution: FiniteDuration) extends Scheduler {

  def sleep(delay: FiniteDuration, task: Runnable): Runnable =
    if (delay.isFinite) {
      //The delay requested is less than the resolution we support
      //so run immediately
      if (delay < resolution) {
        task.run()
        noopCancel
      } else {
        val t = TaskState(task, delay.toMillis + System.currentTimeMillis())

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
    loop()
  }

  thread.setDaemon(true)
  thread.start()

  private def loop(): Unit = {
    //TODO is it nicer if this is just the last idx into the wheel?
    @tailrec
    def loop(previousIdx: Int): Unit = {
      //TODO should we only check this every n iterations?
      if (canceled) {
        return
      }
      val start = System.currentTimeMillis()

      val ops = pendingOps.getAndSet(Noop)
      executeOps(start, ops)

      val idx = toBucketIdx(start)

      @tailrec
      def go(i: Int): Unit = {
        wheel(i).schedule(start)
        if (i < idx) go(i + 1)
      }

      //Can we always start at previous + 1 or do we need to check we're not too early?
      go(previousIdx + 1)

      val end = System.currentTimeMillis()
      val diff = end - start
      if (diff < res) {
        Thread.sleep(res - diff)
      }
      loop(idx)
    }

    loop(toBucketIdx(System.currentTimeMillis()) - 1)
  }

  @inline private def toBucketIdx(ts: Long): Int = (ts % wheelSize).toInt

  @tailrec
  private def executeOps(currentTime: Long, op: Op): Unit =
    op match {
      case Noop => ()
      case Register(state, next) => {
        wheel(toBucketIdx(state.scheduled)).add(state)
        executeOps(currentTime, next)
      }
      case Cancel(state, next) => {
        state.unlink()
        executeOps(currentTime, next)
      }
    }

  private val noopCancel: Runnable = () => ()

  private case class TaskState(
      task: Runnable,
      scheduled: Long,
      var next: TaskState = null,
      var previous: TaskState = null) {

    def unlink(): Unit = {
      if (previous != null) {
        previous.next = next
      }
      if (next != null) {
        next.previous = previous
      }
      next = null
      previous = null
    }
  }

  private class Bucket {

    var head: TaskState = null

    def add(state: TaskState): Unit = {
      state.next = head
      head.previous = state
      head = state
    }

    def schedule(ts: Long): Unit = {
      @tailrec
      def go(state: TaskState): Unit = {
        if (state != null) {
          val next = state.next
          if (state.scheduled < ts) {
            state.unlink()
            try {
              state.task.run()
            } catch {
              case NonFatal(e) => println(s"Caught error $e in io timer")
            }
          }
          go(next)
        }
      }

      go(head)
    }

  }

  private sealed trait Op
  private case class Register(state: TaskState, next: Op) extends Op
  private case class Cancel(state: TaskState, next: Op) extends Op
  private case object Noop extends Op

}
