/*
 * Copyright 2017 Typelevel
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

package cats.effect.util

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.ExecutionContext

/** JavaScript specific [[TrampolinedContext]] implementation. */
private[util] final class TrampolinedContextImpl(underlying: ExecutionContext)
  extends TrampolinedContext {

  private[this] var immediateQueue = mutable.Queue.empty[Runnable]
  private[this] var withinLoop = false

  override def execute(runnable: Runnable): Unit = {
    if (!withinLoop) {
      withinLoop = true
      try immediateLoop(runnable) finally {
        withinLoop = false
      }
    } else {
      immediateQueue.enqueue(runnable)
    }
  }

  private def forkTheRest(): Unit = {
    final class ResumeRun(head: Runnable, rest: mutable.Queue[Runnable])
      extends Runnable {

      def run(): Unit = {
        if (rest.nonEmpty) immediateQueue.enqueue(rest:_*)
        immediateLoop(head)
      }
    }

    if (immediateQueue.nonEmpty) {
      val rest = immediateQueue
      immediateQueue = mutable.Queue.empty[Runnable]
      val head = rest.dequeue()
      underlying.execute(new ResumeRun(head, rest))
    }
  }

  @tailrec
  private def immediateLoop(task: Runnable): Unit = {
    try {
      task.run()
    } catch {
      case ex: Throwable =>
        forkTheRest()
        if (NonFatal(ex)) reportFailure(ex)
        else throw ex
    }

    if (immediateQueue.nonEmpty) {
      val next = immediateQueue.dequeue()
      immediateLoop(next)
    }
  }

  override def reportFailure(t: Throwable): Unit =
    underlying.reportFailure(t)
}
