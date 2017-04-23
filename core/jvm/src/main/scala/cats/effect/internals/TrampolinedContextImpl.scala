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

/** JVM specific [[TrampolinedContext]] implementation. */
private[internals] final class TrampolinedContextImpl(underlying: ExecutionContext)
  extends TrampolinedContext {

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
        startLoop(runnable)

      case some =>
        // If we are already in batching mode, add to stack
        localTasks.set(runnable :: some)
    }

  private def startLoop(runnable: Runnable): Unit = {
    // If we aren't in local mode yet, start local loop
    localTasks.set(Nil)
    BlockContext.withBlockContext(trampolineContext) {
      localRunLoop(runnable)
    }
  }

  @tailrec private def localRunLoop(head: Runnable): Unit = {
    try {
      head.run()
    } catch {
      case ex: Throwable =>
        // Sending everything else to the underlying context
        forkTheRest(null)
        if (NonFatal(ex)) reportFailure(ex) else throw ex
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
    final class ResumeRun(head: Runnable, rest: List[Runnable]) extends Runnable {
      def run(): Unit = {
        localTasks.set(rest)
        localRunLoop(head)
      }
    }

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
}
