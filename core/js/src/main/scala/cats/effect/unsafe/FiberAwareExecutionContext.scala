/*
 * Copyright 2020-2023 Typelevel
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

import scala.collection.mutable
import scala.concurrent.ExecutionContext

private final class FiberAwareExecutionContext(ec: ExecutionContext) extends ExecutionContext {

  def liveTraces(): Map[IOFiber[_], Trace] =
    fiberBag.iterator.filterNot(_.isDone).map(f => f -> f.captureTrace()).toMap

  private[this] val fiberBag = mutable.Set.empty[IOFiber[_]]

  def execute(runnable: Runnable): Unit = runnable match {
    case r: IOFiber[_] =>
      fiberBag += r
      ec execute { () =>
        // We have to remove r _before_ running it, b/c it may be re-enqueued while running
        // B/c JS is single-threaded, nobody can observe the bag while it is running anyway
        fiberBag -= r
        r.run()
      }

    case r => r.run()
  }

  def reportFailure(cause: Throwable): Unit = ec.reportFailure(cause)

}
