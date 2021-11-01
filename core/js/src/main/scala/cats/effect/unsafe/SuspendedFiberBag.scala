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

import scala.scalajs.js

private[effect] sealed abstract class SuspendedFiberBag {

  /**
   * Registers a suspended fiber, tracked by the provided key which is an opaque object which
   * uses reference equality for comparison.
   *
   * @param key
   *   an opaque identifier for the suspended fiber
   * @param fiber
   *   the suspended fiber to be registered
   */
  def monitor(key: AnyRef, fiber: IOFiber[_]): Unit
}

/**
 * Relies on features *standardized* in ES2021, although already offered in many environments
 */
private final class ES2021SuspendedFiberBag extends SuspendedFiberBag {
  private[this] val bag = new IterableWeakMap[AnyRef, js.WeakRef[IOFiber[_]]]

  override def monitor(key: AnyRef, fiber: IOFiber[_]): Unit = {
    bag.set(key, new js.WeakRef(fiber))
  }
}

/**
 * A no-op implementation of an unordered bag used for tracking asynchronously suspended fiber
 * instances on Scala.js. This is used as a fallback.
 */
private final class NoOpSuspendedFiberBag extends SuspendedFiberBag {
  override def monitor(key: AnyRef, fiber: IOFiber[_]): Unit = ()
}

private[effect] object SuspendedFiberBag {
  def apply(): SuspendedFiberBag =
    if (IterableWeakMap.isAvailable)
      new ES2021SuspendedFiberBag
    else
      new NoOpSuspendedFiberBag
}
