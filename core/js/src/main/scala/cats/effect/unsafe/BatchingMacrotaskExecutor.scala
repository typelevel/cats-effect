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
import scala.util.control.NonFatal

import java.util.ArrayDeque

private[effect] final class BatchingMacrotaskExecutor(batchSize: Int)
    extends ExecutionContextExecutor {

  private[this] val MicrotaskExecutor = QueueExecutionContext.promises()

  private[this] var needsReschedule = true
  private[this] val tasks = new ArrayDeque[Runnable](batchSize)

  private[this] def executeBatchTask = _executeBatchTask
  private[this] val _executeBatchTask: Runnable = () => {
    // do up to batchSize tasks
    var i = 0
    while (i < batchSize && !tasks.isEmpty()) {
      val runnable = tasks.poll()
      try runnable.run()
      catch {
        case t if NonFatal(t) => reportFailure(t)
        case t: Throwable => IOFiber.onFatalFailure(t)
      }
      i += 1
    }

    if (!tasks.isEmpty()) // we'll be right back after the (post) message
      MacrotaskExecutor.execute(executeBatchTask)
    else
      needsReschedule = true
  }

  def execute(runnable: Runnable): Unit =
    MacrotaskExecutor.execute(monitor(runnable))

  def schedule(runnable: Runnable): Unit = {
    tasks.addLast(monitor(runnable))
    if (needsReschedule) {
      needsReschedule = false
      // run immediately after the current task suspends
      MicrotaskExecutor.execute(executeBatchTask)
    }
  }

  def reportFailure(t: Throwable): Unit = MacrotaskExecutor.reportFailure(t)

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
