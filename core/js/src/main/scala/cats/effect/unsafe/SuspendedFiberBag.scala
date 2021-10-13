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

import scala.collection.mutable

/**
 * A simple implementation of an unordered bag used for tracking asynchronously suspended fiber
 * instances on Scala.js. This bag is backed by a mutable hash map and delegates all
 * functionality to it. Because Scala.js runs in a single-threaded environment, there's no need
 * for any synchronization. Ideally, we would have liked the Scala.js implementation to also use
 * a `java.util.WeakHashMap` so that the removal of resumed fibers is handled automatically, but
 * weak references are still not available in Scala.js.
 */
private final class SuspendedFiberBag {
  private[this] val bag: mutable.Map[AnyRef, IOFiber[_]] =
    mutable.Map.empty

  /**
   * Registers a suspended fiber, tracked by the provided key which is an opaque object which
   * uses reference equality for comparison.
   *
   * @param key
   *   an opaque identifier for the suspended fiber
   * @param fiber
   *   the suspended fiber to be registered
   */
  def monitor(key: AnyRef, fiber: IOFiber[_]): Unit = {
    bag(key) = fiber
  }

  /**
   * Deregisters a resumed fiber, tracked by the provided key which is an opaque object which
   * uses reference equality for comparison.
   *
   * @param key
   *   an opaque identifier for the resumed fiber
   */
  def unmonitor(key: AnyRef): Unit = {
    bag -= key
  }
}
