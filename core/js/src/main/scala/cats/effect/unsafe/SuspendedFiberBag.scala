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

/**
 * A no-op implementation of an unordered bag used for tracking asynchronously suspended fiber
 * instances on Scala.js. We will iterate on this in the future.
 */
private[effect] final class SuspendedFiberBag {

  /**
   * Registers a suspended fiber, tracked by the provided key which is an opaque object which
   * uses reference equality for comparison.
   *
   * @param key
   *   an opaque identifier for the suspended fiber
   * @param fiber
   *   the suspended fiber to be registered
   */
  @nowarn("cat=unused-params")
  def monitor(key: AnyRef, fiber: IOFiber[_]): Unit = {}

  /**
   * Deregisters a resumed fiber, tracked by the provided key which is an opaque object which
   * uses reference equality for comparison.
   *
   * @param key
   *   an opaque identifier for the resumed fiber
   */
  @nowarn("cat=unused-params")
  def unmonitor(key: AnyRef): Unit = {}

  def contents(): Set[IOFiber[_]] = Set()
}
