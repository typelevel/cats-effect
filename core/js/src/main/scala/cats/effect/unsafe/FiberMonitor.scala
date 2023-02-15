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
import scala.scalajs.{js, LinkingInfo}

private[effect] sealed abstract class FiberMonitor extends FiberMonitorShared {

  /**
   * Registers a suspended fiber.
   *
   * @param fiber
   *   the suspended fiber to be registered
   * @return
   *   a handle for deregistering the fiber on resumption
   */
  def monitorSuspended(fiber: IOFiber[_]): WeakBag.Handle

  /**
   * Obtains a snapshot of the fibers currently live on the [[IORuntime]] which this fiber
   * monitor instance belongs to.
   *
   * @return
   *   a textual representation of the runtime snapshot, `None` if a snapshot cannot be obtained
   */
  def liveFiberSnapshot(print: String => Unit): Unit
}

/**
 * Relies on features *standardized* in ES2021, although already offered in many environments
 */
private final class ES2021FiberMonitor(
    // A reference to the compute pool of the `IORuntime` in which this suspended fiber bag
    // operates. `null` if the compute pool of the `IORuntime` is not a `BatchingMacrotaskExecutor`.
    private[this] val compute: BatchingMacrotaskExecutor
) extends FiberMonitor {
  private[this] val bag = new WeakBag[IOFiber[_]]()

  override def monitorSuspended(fiber: IOFiber[_]): WeakBag.Handle =
    bag.insert(fiber)

  def foreignTraces(): Map[IOFiber[_], Trace] = {
    val foreign = mutable.Map.empty[IOFiber[Any], Trace]
    bag.forEach(fiber =>
      if (!fiber.isDone) foreign += (fiber.asInstanceOf[IOFiber[Any]] -> fiber.captureTrace()))
    foreign.toMap
  }

  def liveFiberSnapshot(print: String => Unit): Unit =
    Option(compute).foreach { compute =>
      val queued = compute.liveTraces()
      val rawForeign = foreignTraces()

      // We trust the sources of data in the following order, ordered from
      // most trustworthy to least trustworthy.
      // 1. Fibers from the macrotask executor
      // 2. Fibers from the foreign fallback weak GC map

      val allForeign = rawForeign -- queued.keys
      val (suspended, foreign) = allForeign.partition { case (f, _) => f.get() }

      printFibers(queued, "YIELDING")(print)
      printFibers(foreign, "YIELDING")(print)
      printFibers(suspended, "WAITING")(print)

      val globalStatus =
        s"Global: enqueued ${queued.size + foreign.size}, waiting ${suspended.size}"

      print(doubleNewline)
      print(globalStatus)
      print(newline)
    }

}

/**
 * A no-op implementation of an unordered bag used for tracking asynchronously suspended fiber
 * instances on Scala.js. This is used as a fallback.
 */
private final class NoOpFiberMonitor extends FiberMonitor {
  override def monitorSuspended(fiber: IOFiber[_]): WeakBag.Handle = () => ()
  def liveFiberSnapshot(print: String => Unit): Unit = ()
}

private[effect] object FiberMonitor {
  def apply(compute: ExecutionContext): FiberMonitor = {
    if (LinkingInfo.developmentMode && weakRefsAvailable) {
      if (compute.isInstanceOf[BatchingMacrotaskExecutor]) {
        val bmec = compute.asInstanceOf[BatchingMacrotaskExecutor]
        new ES2021FiberMonitor(bmec)
      } else {
        new ES2021FiberMonitor(null)
      }
    } else {
      new NoOpFiberMonitor()
    }
  }

  private[this] final val Undefined = "undefined"

  /**
   * Feature-tests for all the required, well, features :)
   */
  private[unsafe] def weakRefsAvailable: Boolean =
    js.typeOf(js.Dynamic.global.WeakRef) != Undefined &&
      js.typeOf(js.Dynamic.global.FinalizationRegistry) != Undefined
}
