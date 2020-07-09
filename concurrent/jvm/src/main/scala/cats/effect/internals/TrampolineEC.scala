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

package cats.effect.internals

import cats.effect.internals.TrampolineEC.JVMTrampoline
import scala.concurrent.{BlockContext, CanAwait, ExecutionContext}

/**
 * INTERNAL API — a [[scala.concurrent.ExecutionContext]] implementation
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
final private[effect] class TrampolineEC private (underlying: ExecutionContext) extends ExecutionContext {
  private[this] val trampoline =
    new ThreadLocal[Trampoline]() {
      override def initialValue(): Trampoline =
        new JVMTrampoline(underlying)
    }

  override def execute(runnable: Runnable): Unit =
    trampoline.get().execute(runnable)
  override def reportFailure(t: Throwable): Unit =
    underlying.reportFailure(t)
}

private[effect] object TrampolineEC {

  /**
   * [[TrampolineEC]] instance that executes everything
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
   *
   * INTERNAL API.
   */
  val immediate: TrampolineEC =
    new TrampolineEC(new ExecutionContext {
      def execute(r: Runnable): Unit = r.run()
      def reportFailure(e: Throwable): Unit =
        Logger.reportFailure(e)
    })

  /**
   * Overrides [[Trampoline]] to be `BlockContext`-aware.
   *
   * INTERNAL API.
   */
  final private class JVMTrampoline(underlying: ExecutionContext) extends Trampoline(underlying) {
    private[this] val trampolineContext: BlockContext =
      new BlockContext {
        def blockOn[T](thunk: => T)(implicit permission: CanAwait): T = {
          // In case of blocking, execute all scheduled local tasks on
          // a separate thread, otherwise we could end up with a dead-lock
          forkTheRest()
          thunk
        }
      }

    override def startLoop(runnable: Runnable): Unit =
      BlockContext.withBlockContext(trampolineContext) {
        super.startLoop(runnable)
      }
  }
}
