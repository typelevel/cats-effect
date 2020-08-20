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

/**
 * Common abstraction over a `scala.concurrent.ExecutionContext` and `WorkStealingThreadPool` for a unified API. Using
 * this abstraction, we can avoid branching when constructing `IOFiber` instances making them unaware of their
 * underlying executor. That way, an `IOFiber` can run on a `WorkStealingThreadPool` in an `IOApp` on the JVM,
 * a custom user provided `scala.concurrent.ExecutionContext`, a `PolyfillExecutionContext` on JS, or a test
 * instance of a `scala.concurrent.ExecutionContext` in tests.
 */
private[effect] abstract class IOExecutionContext {

  /**
   * Expose the wrapped `scala.concurrent.ExecutionContext` through composition, not inheritance, in order to leverage
   * the type checker when dealing with potentially unsafe operations in `IOFiber` and when casting to a `TestContext`
   * in tests.
   */
  protected[effect] def underlying: ExecutionContext

  /**
   * Execute the given fiber in this `IOExecutionContext`. The default implementation delegates to the underlying
   * `ExecutionContext#execute` because an `IOFiber` is a `java.lang.Runnable`. Overriden in `WorkStealingThreadPool`
   * for the correct semantics for work-stealing.
   */
  protected[effect] def executeFiber(fiber: IOFiber[_]): Unit =
    underlying.execute(fiber)

  /**
   * Push the given fiber at the back of the already executing worker queue. The default implementation delegates to
   * the underlying `ExecutionContext#execute` because an `IOFiber` is a `java.lang.Runnable`. Overriden in
   * `WorkStealingThreadPool` for the correct semantics for work-stealing.
   */
  protected[effect] def reschedule(fiber: IOFiber[_], queue: WorkQueue): Unit = {
    val _ = queue
    underlying.execute(fiber)
  }
}

private[effect] object IOExecutionContext {

  /**
   * Constructs an `IOExecutionContext` by wrapping a `scala.concurrent.ExecutionContext` and delegating to its
   * `execute` method. Special care needs to be taken in order to avoid wrapping a `WorkStealingThreadPool` which does
   * indeed extend `ExecutionContext` for efficiency reasons.
   */
  def wrap(ec: ExecutionContext): IOExecutionContext =
    if (ec.isInstanceOf[WorkStealingThreadPool]) ec.asInstanceOf[WorkStealingThreadPool]
    else
      new IOExecutionContext {
        protected[effect] def underlying: ExecutionContext = ec
      }
}
