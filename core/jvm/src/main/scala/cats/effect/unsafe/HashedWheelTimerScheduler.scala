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

  def sleep(delay: FiniteDuration, task: Runnable): Runnable = {
    if (!canceled) {
      if (delay.isFinite) {
        //The delay requested is less than the resolution we support
        //so run immediately
        if (delay < resolution) {
          task.run()
          noopCancel
        } else {
          val t = TaskState(task, delay.toMillis + nowMillis())
          println(s"Running task at ${t.scheduled}")

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
      else {
        println("infinite delay")
        noopCancel
      }
    } else {
      sys.error("Hashed wheel scheduler is shutdown")
    }
  }

  def nowMillis() = System.currentTimeMillis()

  def monotonicNanos() = System.nanoTime()

  def shutdown(): Unit = {
    println("shutdown")
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
  private val resolutionMillis: Long = resolution.toMillis
  private val invResolutionMillis: Double = 1.0 / resolutionMillis

  private val wheel: Array[Bucket] = (0.until(wheelSize)).map(_ => new Bucket()).toArray

  private val pendingOps: AtomicReference[Op] = new AtomicReference[Op](Noop)

  //TODO would it be better if shutdown was another op to be inserted into the queue?
  @volatile private var canceled = false

  private val thread = new Thread("io-scheduler") {
    override def run(): Unit = loop()
  }

  thread.setDaemon(true)
  thread.setPriority(Thread.MAX_PRIORITY)
  thread.start()

  private def loop(): Unit = {
    @tailrec
    def loop(previousTicks: Long): Unit = {
      //TODO should we only check this every n iterations?
      if (!canceled) {
        val startTime = nowMillis()
        val ticks = (startTime * invResolutionMillis).toLong
        val iters = Math.min(ticks - previousTicks, wheelSize).toInt
        // println(s"Scheduling for $start")

        val ops = pendingOps.getAndSet(Noop)
        executeOps(ops)

        @tailrec
        def go(i: Int): Unit = {
          if (i < iters) {
            println(s"scheduling bucket ${ticksToBucketIdx(previousTicks + i)} at $startTime")
            wheel(ticksToBucketIdx(previousTicks + i)).schedule(startTime)
            go(i + 1)
          }
        }

        go(0)

        val curr = nowMillis()
        val target = (ticks + 1) * resolutionMillis
        if (curr < target) {
          //TODO do we need to handle thread interrupted ex?
          Thread.sleep(target - curr)
        }
        loop(ticks)
      } else {
        //null out to avoid leaks
        (0.until(wheelSize)).foreach { n =>
          wheel(n) = null
        }
      }
    }

    //Make sure we don't miss the current bucket on startup
    loop(((nowMillis() - resolutionMillis) * invResolutionMillis).toLong)
  }

  @inline private def tsToBucketIdx(ts: Long): Int =
    ((ts * invResolutionMillis).toLong % wheelSize).toInt

  @inline private def ticksToBucketIdx(ticks: Long): Int =
    (ticks % wheelSize).toInt

  @tailrec
  private def executeOps(op: Op): Unit =
    op match {
      case Noop => ()
      case Register(state, next) => {
        println(s"Scheduling task to bucket ${tsToBucketIdx(state.scheduled)}")
        wheel(tsToBucketIdx(state.scheduled)).add(state)
        executeOps(next)
      }
      case Cancel(state, next) => {
        // println(s"Canceling task")
        state.unlink()
        executeOps(next)
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
      if (head != null) {
        head.previous = state
      }
      head = state
    }

    def schedule(ts: Long): Unit = {
      @tailrec
      def go(state: TaskState): Unit = {
        if (state != null) {
          val next = state.next
          if (state.scheduled <= ts) {
            state.unlink()
            try {
              // println("Running task")
              state.task.run()
            } catch {
              case NonFatal(e) => println(s"Caught error $e in io timer")
            }
          } else {
            println(s"too early: current $ts scheduled: ${state.scheduled}")
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
