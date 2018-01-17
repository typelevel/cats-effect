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

package cats.effect.internals

import scala.annotation.tailrec
import scala.concurrent.{BlockContext, CanAwait, ExecutionContext}

/** INTERNAL API — a [[scala.concurrent.ExecutionContext]] implementation
  * that executes runnables immediately, on the current thread,
  * by means of a trampoline implementation.
  *
  * Can be used in some cases to keep the asynchronous execution
  * on the current thread, as an optimization, but be warned,
  * you have to know what you're doing.
  *
  * This is the JVM-specific implementation, for which we
  * need a `ThreadLocal` and Scala's `BlockingContext`.
  */
private[effect] final class TrampolineEC private (underlying: ExecutionContext)
  extends ExecutionContext {

  private[this] val localTasks = new ThreadLocal[List[Runnable]]()

  private[this] val trampolineContext: BlockContext =
    new BlockContext {
      def blockOn[T](thunk: => T)(implicit permission: CanAwait): T = {
        // In case of blocking, execute all scheduled local tasks on
        // a separate thread, otherwise we could end up with a dead-lock
        forkTheRest(Nil)
        thunk
      }
    }

  override def execute(runnable: Runnable): Unit =
    localTasks.get match {
      case null =>
        // If we aren't in local mode yet, start local loop
        localTasks.set(Nil)
        BlockContext.withBlockContext(trampolineContext) {
          localRunLoop(runnable)
        }
      case some =>
        // If we are already in batching mode, add to stack
        localTasks.set(runnable :: some)
    }

  @tailrec private def localRunLoop(head: Runnable): Unit = {
    try { head.run() } catch {
      case NonFatal(ex) =>
        // Sending everything else to the underlying context
        forkTheRest(null)
        reportFailure(ex)
    }

    localTasks.get() match {
      case null => ()
      case Nil =>
        localTasks.set(null)
      case h2 :: t2 =>
        localTasks.set(t2)
        localRunLoop(h2)
    }
  }

  private def forkTheRest(newLocalTasks: Nil.type): Unit = {
    val rest = localTasks.get()
    localTasks.set(newLocalTasks)

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
      localTasks.set(rest)
      localRunLoop(head)
    }
  }
}

private[effect] object TrampolineEC {
  /** [[TrampolineEC]] instance that executes everything
    * immediately, on the current thread.
    *
    * Implementation notes:
    *
    *  - if too many `blocking` operations are chained, at some point
    *    the implementation will trigger a stack overflow error
    *  - `reportError` re-throws the exception in the hope that it
    *    will get caught and reported by the underlying thread-pool,
    *    because there's nowhere it could report that error safely
    *    (i.e. `System.err` might be routed to `/dev/null` and we'd
    *    have no way to override it)
    */
  val immediate: TrampolineEC =
    new TrampolineEC(new ExecutionContext {
      def execute(r: Runnable): Unit = r.run()
      def reportFailure(e: Throwable): Unit = throw e
    })
}