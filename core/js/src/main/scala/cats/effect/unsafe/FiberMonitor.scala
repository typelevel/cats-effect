/*
 * Copyright 2020-2021 Typelevel
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
import scala.scalajs.js
import scala.scalajs.LinkingInfo

private[effect] sealed abstract class FiberMonitor extends FiberMonitorShared {

  /**
   * Registers a suspended fiber, tracked by the provided key which is an opaque object which
   * uses reference equality for comparison.
   *
   * @param key
   *   an opaque identifier for the suspended fiber
   * @param fiber
   *   the suspended fiber to be registered
   */
  def monitorSuspended(key: AnyRef, fiber: IOFiber[_]): Unit

  def liveFiberSnapshot(): Option[String]
}

/**
 * Relies on features *standardized* in ES2021, although already offered in many environments
 */
private final class ES2021FiberMonitor(
    // A reference to the compute pool of the `IORuntime` in which this suspended fiber bag
    // operates. `null` if the compute pool of the `IORuntime` is not a `FiberAwareExecutionContext`.
    private[this] val compute: FiberAwareExecutionContext
) extends FiberMonitor {
  private[this] val bag = new IterableWeakMap[AnyRef, js.WeakRef[IOFiber[_]]]

  override def monitorSuspended(key: AnyRef, fiber: IOFiber[_]): Unit = {
    bag.set(key, new js.WeakRef(fiber))
  }

  private[this] def foreignFibers(): Set[IOFiber[_]] =
    bag.entries().flatMap(_._2.deref().toOption).toSet

  /**
   * Obtains a snapshot of the fibers currently live on the [[IORuntime]] which this fiber
   * monitor instance belongs to.
   *
   * @return
   *   a textual representation of the runtime snapshot, `None` if a snapshot cannot be obtained
   */
  def liveFiberSnapshot(): Option[String] =
    Option(compute).map { compute =>
      val queued = compute.liveFibers()
      val rawForeign = foreignFibers()

      // We trust the sources of data in the following order, ordered from
      // most trustworthy to least trustworthy.
      // 1. Fibers from the macrotask executor
      // 2. Fibers from the foreign fallback weak GC map

      val allForeign = rawForeign -- queued
      val suspended = allForeign.filter(_.get())
      val foreign = allForeign.filterNot(_.get())

      val liveFiberSnapshotHeader = s"Live Fiber Snapshot$doubleNewline"

      val queuedString =
        fibersString(queued, "Fibers enqueued on Macrotask Executor", "YIELDING")

      val suspendedForeignString = suspendedForeignFiberString(suspended, foreign)

      liveFiberSnapshotHeader ++ queuedString ++ suspendedForeignString
    }

}

/**
 * A no-op implementation of an unordered bag used for tracking asynchronously suspended fiber
 * instances on Scala.js. This is used as a fallback.
 */
private final class NoOpFiberMonitor extends FiberMonitor {
  override def monitorSuspended(key: AnyRef, fiber: IOFiber[_]): Unit = ()
  def liveFiberSnapshot(): Option[String] = None
}

private[effect] object FiberMonitor {

  def apply(compute: ExecutionContext): FiberMonitor = {
    if (LinkingInfo.developmentMode && IterableWeakMap.isAvailable) {
      if (compute.isInstanceOf[FiberAwareExecutionContext]) {
        val faec = compute.asInstanceOf[FiberAwareExecutionContext]
        new ES2021FiberMonitor(faec)
      } else {
        new ES2021FiberMonitor(null)
      }
    } else {
      new NoOpFiberMonitor()
    }
  }
}
