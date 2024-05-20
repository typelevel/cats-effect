/*
 * Copyright 2020-2024 Typelevel
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
import scala.scalajs.{js, LinkingInfo}
import scala.util.control.NonFatal

/**
 * An `ExecutionContext` that improves throughput by providing a method to `schedule` fibers to
 * execute in batches, instead of one task per event loop iteration. This optimization targets
 * the typical scenario where a UI or I/O event handler starts/resumes a small number of
 * short-lived fibers and then yields to the event loop.
 *
 * This `ExecutionContext` also maintains a fiber bag in development mode to enable fiber dumps.
 *
 * @param batchSize
 *   the maximum number of batched runnables to execute before yielding to the event loop
 */
private[effect] final class BatchingMacrotaskExecutor(
    batchSize: Int,
    reportFailure0: Throwable => Unit
) extends ExecutionContextExecutor
    with FiberExecutor {

  private[this] val queueMicrotask: js.Function1[js.Function0[Any], Any] =
    if (js.typeOf(js.Dynamic.global.queueMicrotask) == "function")
      js.Dynamic.global.queueMicrotask.asInstanceOf[js.Function1[js.Function0[Any], Any]]
    else {
      val resolved = js.Dynamic.global.Promise.resolve(())
      task => resolved.`then`(task)
    }

  /**
   * Whether the `executeBatchTask` needs to be rescheduled
   */
  private[this] var needsReschedule = true
  private[this] val fibers = new JSArrayQueue[IOFiber[_]]

  private[this] val executeBatchTaskRunnable = new Runnable {
    def run() = {
      // do up to batchSize tasks
      var i = 0
      while (i < batchSize && !fibers.isEmpty()) {
        val fiber = fibers.take()
        try fiber.run()
        catch {
          case t if NonFatal(t) => reportFailure(t)
          case t: Throwable => IOFiber.onFatalFailure(t)
        }
        i += 1
      }

      if (!fibers.isEmpty()) // we'll be right back after this (post) message
        MacrotaskExecutor.execute(this)
      else // the batch task will need to be rescheduled when more fibers arrive
        needsReschedule = true

      // yield to the event loop
    }
  }

  private[this] val executeBatchTaskJSFunction: js.Function0[Any] =
    () => executeBatchTaskRunnable.run()

  /**
   * Execute the `runnable` in the next iteration of the event loop.
   */
  def execute(runnable: Runnable): Unit =
    MacrotaskExecutor.execute(monitor(runnable))

  /**
   * Schedule the `fiber` for the next available batch. This is often the currently executing
   * batch.
   */
  def schedule(fiber: IOFiber[_]): Unit = {
    fibers.offer(fiber)

    if (needsReschedule) {
      needsReschedule = false
      // start executing the batch immediately after the currently running task suspends
      // this is safe b/c `needsReschedule` is set to `true` only upon yielding to the event loop
      queueMicrotask(executeBatchTaskJSFunction)
      ()
    }
  }

  def reportFailure(t: Throwable): Unit = reportFailure0(t)

  def liveTraces(): Map[IOFiber[_], Trace] = {
    val traces = Map.newBuilder[IOFiber[_], Trace]
    fibers.foreach(f => if (!f.isDone) traces += f -> f.captureTrace())
    fiberBag.foreach(f => if (!f.isDone) traces += f -> f.captureTrace())
    traces.result()
  }

  @inline private[this] def monitor(runnable: Runnable): Runnable =
    if (LinkingInfo.developmentMode)
      if (fiberBag ne null)
        runnable match {
          case r: IOFiber[_] =>
            fiberBag += r
            () => {
              // We have to remove r _before_ running it, b/c it may be re-enqueued while running
              // b/c JS is single-threaded, nobody can observe the bag while the fiber is running anyway
              fiberBag -= r
              r.run()
            }
          case _ => runnable
        }
      else runnable
    else
      runnable

  private[this] val fiberBag =
    if (LinkingInfo.developmentMode)
      if (TracingConstants.isStackTracing && FiberMonitor.weakRefsAvailable)
        mutable.Set.empty[IOFiber[_]]
      else
        null
    else
      null

}
