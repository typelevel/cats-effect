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

import scala.concurrent.ExecutionContext

import java.util.concurrent.atomic.AtomicBoolean

private[effect] abstract class IOFiberPlatform[A] extends AtomicBoolean(false) {
  this: IOFiber[A] =>

  /**
   * Explicit suspension key object reference due to lack of `WeakHashMap` support in Scala.js.
   * This reference is set when a fiber is suspended (and the key is used to register the fiber
   * in the global suspended fiber bag), and cleared when the fiber is resumed.
   */
  private[this] final var suspensionKey: AnyRef = null

  /**
   * Registers the suspended fiber in the global suspended fiber bag and sets the suspension key
   * object reference.
   */
  protected final def monitor(key: AnyRef): Unit = {
    val fiber = this
    fiber.runtimeForwarder.suspendedFiberBag.monitor(key, fiber)
    suspensionKey = key
  }

  /**
   * Deregisters the suspended fiber from the global suspended fiber bag and clears the
   * suspension key object reference.
   */
  protected final def unmonitor(): Unit = {
    val fiber = this
    val key = suspensionKey
    fiber.runtimeForwarder.suspendedFiberBag.unmonitor(key)
    suspensionKey = null
  }

  // in theory this code should never be hit due to the override in IOCompanionPlatform
  def interruptibleImpl(cur: IO.Blocking[Any], blockingEc: ExecutionContext): IO[Any] = {
    val _ = blockingEc
    IO(cur.thunk())
  }
}
