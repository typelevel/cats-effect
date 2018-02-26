/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

/** INTERNAL API — a [[scala.concurrent.ExecutionContext]] implementation
  * that executes runnables immediately, on the current thread,
  * by means of a trampoline implementation.
  *
  * Can be used in some cases to keep the asynchronous execution
  * on the current thread, as an optimization, but be warned,
  * you have to know what you're doing.
  *
  * This is the JavaScript-specific implementation, for which we
  * don't need a `ThreadLocal` or Scala's `BlockingContext`.
  */
private[effect] final class TrampolineEC private (underlying: ExecutionContext)
  extends ExecutionContext {

  // Starts with `null`!
  private[this] var localTasks: List[Runnable] = _

  override def execute(runnable: Runnable): Unit =
    localTasks match {
      case null =>
        // If we aren't in local mode yet, start local loop
        localTasks = Nil
        localRunLoop(runnable)
      case some =>
        // If we are already in batching mode, add to stack
        localTasks = runnable :: some
    }


  @tailrec private def localRunLoop(head: Runnable): Unit = {
    try { head.run() } catch {
      case NonFatal(ex) =>
        // Sending everything else to the underlying context
        forkTheRest(null)
        reportFailure(ex)
    }

    localTasks match {
      case null => ()
      case Nil =>
        localTasks = null
      case h2 :: t2 =>
        localTasks = t2
        localRunLoop(h2)
    }
  }

  private def forkTheRest(newLocalTasks: Nil.type): Unit = {
    val rest = localTasks
    localTasks = newLocalTasks

    rest match {
      case null | Nil => ()
      case head :: tail =>
        underlying.execute(new ResumeRun(head, tail))
    }
  }

  override def reportFailure(t: Throwable): Unit =
    underlying.reportFailure(t)

  private final class ResumeRun(head: Runnable, rest: List[Runnable])
    extends Runnable {

    def run(): Unit = {
      localTasks = rest
      localRunLoop(head)
    }
  }
}

private[effect] object TrampolineEC {
  /** [[TrampolineEC]] instance that executes everything
    * immediately, on the current call stack.
    */
  val immediate: TrampolineEC =
    new TrampolineEC(new ExecutionContext {
      def execute(r: Runnable): Unit = r.run()
      def reportFailure(e: Throwable): Unit =
        Logger.reportFailure(e)
    })
}