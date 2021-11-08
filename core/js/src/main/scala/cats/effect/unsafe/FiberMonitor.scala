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

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.LinkingInfo

private[effect] sealed abstract class FiberMonitor {

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
}

/**
 * Relies on features *standardized* in ES2021, although already offered in many environments
 */
@nowarn("cat=unused-params")
private final class ES2021FiberMonitor(
    // A reference to the compute pool of the `IORuntime` in which this suspended fiber bag
    // operates. `null` if the compute pool of the `IORuntime` is not a `FiberAwareExecutionContext`.
    private[this] val compute: FiberAwareExecutionContext
) extends FiberMonitor {
  private[this] val bag = new IterableWeakMap[AnyRef, js.WeakRef[IOFiber[_]]]

  override def monitorSuspended(key: AnyRef, fiber: IOFiber[_]): Unit = {
    bag.set(key, new js.WeakRef(fiber))
  }

  @nowarn("cat=unused")
  private[this] def foreignFibers(): Set[IOFiber[_]] =
    bag.entries().flatMap(_._2.deref().toOption).toSet

}

/**
 * A no-op implementation of an unordered bag used for tracking asynchronously suspended fiber
 * instances on Scala.js. This is used as a fallback.
 */
private final class NoOpFiberMonitor extends FiberMonitor {
  override def monitorSuspended(key: AnyRef, fiber: IOFiber[_]): Unit = ()
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
