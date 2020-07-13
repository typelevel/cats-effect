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
final private[effect] class TrampolineEC private (underlying: ExecutionContext) extends ExecutionContext {
  private[this] val trampoline = new Trampoline(underlying)

  override def execute(runnable: Runnable): Unit =
    trampoline.execute(runnable)
  override def reportFailure(t: Throwable): Unit =
    underlying.reportFailure(t)
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
