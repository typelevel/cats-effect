/*
 * Copyright 2020-2022 Typelevel
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

import cats.effect.tracing.TracingConstants

import org.scalajs.macrotaskexecutor.MacrotaskExecutor

import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor
import scala.scalajs.LinkingInfo
import scala.scalajs.concurrent.QueueExecutionContext

private[effect] final class BatchingMacrotaskExecutor(batchSize: Int)
    extends ExecutionContextExecutor {

  private[this] val MicrotaskExecutor = QueueExecutionContext.promises()

  private[this] var counter = 0

  private[this] val resetCounter: Runnable = () => counter = 0

  def reportFailure(t: Throwable): Unit = MacrotaskExecutor.reportFailure(t)

  def execute(runnable: Runnable): Unit =
    MacrotaskExecutor.execute(monitor(runnable))

  def schedule(runnable: Runnable): Unit = {
    if (counter < batchSize) {
      MicrotaskExecutor.execute(monitor(runnable))
    } else {
      if (counter == batchSize)
        MacrotaskExecutor.execute(resetCounter)
      MacrotaskExecutor.execute(monitor(runnable))
    }
    counter += 1
  }

  def liveTraces(): Map[IOFiber[_], Trace] =
    fiberBag.iterator.filterNot(_.isDone).map(f => f -> f.captureTrace()).toMap

  @inline private[this] def monitor(runnable: Runnable): Runnable =
    if (LinkingInfo.developmentMode)
      if (fiberBag ne null)
        runnable match {
          case r: IOFiber[_] =>
            fiberBag += r
            () => {
              // We have to remove r _before_ running it, b/c it may be re-enqueued while running
              // B/c JS is single-threaded, nobody can observe the bag while it is running anyway
              fiberBag -= r
              r.run()
            }
          case _ => runnable
        }
      else runnable
    else
      runnable

  private[this] val fiberBag =
    if (LinkingInfo.developmentMode && TracingConstants.isStackTracing && FiberMonitor.weakRefsAvailable)
      mutable.Set.empty[IOFiber[_]]
    else
      null

}
