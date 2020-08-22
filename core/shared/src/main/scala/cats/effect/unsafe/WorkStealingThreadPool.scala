/*
 * Copyright 2020 Typelevel
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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport

/**
 * Work-stealing thread pool which manages a pool of `WorkerThread`s for the specific purpose of executing `IOFiber`s
 * with work-stealing semantics.
 */
private[effect] final class WorkStealingThreadPool(
    threadCount: Int,
    threadPrefix: String,
    self0: => IORuntime
) extends IOExecutionContext
    with ExecutionContext {

  private[this] lazy val self: IORuntime = self0
  @volatile private[unsafe] var done: Boolean = false
  private[this] val workerThreads: Array[WorkerThread] = new Array[WorkerThread](threadCount)
  private[this] val externalQueue: ConcurrentLinkedQueue[IOFiber[_]] =
    new ConcurrentLinkedQueue[IOFiber[_]]()
  private[unsafe] val parkedThreads: ConcurrentLinkedQueue[WorkerThread] =
    new ConcurrentLinkedQueue[WorkerThread]()

  {
    var i = 0
    while (i < threadCount) {
      val index = i
      val thread = new WorkerThread(index, this)
      thread.setName(s"$threadPrefix-$index")
      thread.setDaemon(true)
      workerThreads(i) = thread
      i += 1
    }

    i = 0
    while (i < threadCount) {
      workerThreads(i).start()
      i += 1
    }
  }

  private[unsafe] def stealFromOtherWorkQueue(from: Int): IOFiber[_] = {
    var i = 0
    while (i < threadCount) {
      val index = (from + i + 1) % threadCount
      val res = workerThreads(index).queue.steal()
      if (res != null) return res
      i += 1
    }
    null
  }

  private[unsafe] def stealFromExternalQueue(): IOFiber[_] =
    externalQueue.poll()

  private[unsafe] def park(thread: WorkerThread): Unit = {
    parkedThreads.offer(thread)
    LockSupport.park(this)
  }

  override protected[effect] def underlying: ExecutionContext = this

  override protected[effect] def executeFiber(fiber: IOFiber[_]): Unit = {
    val _ = externalQueue.offer(fiber)
    LockSupport.unpark(parkedThreads.poll())
  }

  override protected[effect] def reschedule(fiber: IOFiber[_], queue: WorkQueue): Unit =
    queue.push(fiber)

  def execute(runnable: Runnable): Unit = {

    val fiber = IO(runnable.run()).unsafeRunFiber(true)(_.fold(reportFailure(_), _ => ()))(self)
    executeFiber(fiber)
  }

  def reportFailure(cause: Throwable): Unit = {
    cause.printStackTrace()
  }

  def shutdown(): Unit = {
    done = true
    workerThreads.foreach(_.interrupt())
  }
}
