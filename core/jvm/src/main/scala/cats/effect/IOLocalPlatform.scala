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

import IOFiberConstants.ioLocalPropagation

private[effect] trait IOLocalPlatform[A] { self: IOLocal[A] =>

  /**
   * Returns a [[java.lang.ThreadLocal]] view of this [[IOLocal]] that allows to unsafely get,
   * set, and remove (aka reset) the value in the currently running fiber. The system property
   * `cats.effect.ioLocalPropagation` must be `true`, otherwise throws an
   * [[java.lang.UnsupportedOperationException]].
   */
  def unsafeThreadLocal(): ThreadLocal[A] = if (ioLocalPropagation)
    new ThreadLocal[A] {
      override def get(): A = {
        val fiber = IOFiber.currentIOFiber()
        val state = if (fiber ne null) fiber.getLocalState() else IOLocalState.empty
        self.getOrDefault(state)
      }

      override def set(value: A): Unit = {
        val fiber = IOFiber.currentIOFiber()
        if (fiber ne null) {
          fiber.setLocalState(self.set(fiber.getLocalState(), value))
        }
      }

      override def remove(): Unit = {
        val fiber = IOFiber.currentIOFiber()
        if (fiber ne null) {
          fiber.setLocalState(self.reset(fiber.getLocalState()))
        }
      }
    }
  else
    throw new UnsupportedOperationException(
      "IOLocal-ThreadLocal propagation is disabled.\n" +
        "Enable by setting cats.effect.ioLocalPropagation=true."
    )

}
