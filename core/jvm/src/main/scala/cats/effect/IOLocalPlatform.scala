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
   * Creates a [[java.lang.ThreadLocal]] based on this [[IOLocal]]. This allows to unsafely get,
   * set, and remove (aka reset) the [[IOLocal]]'s value in the currently running fiber. When
   * used outside of a fiber, behaves like an ordinary `ThreadLocal`.
   *
   * The system property `cats.effect.ioLocalPropagation` must be `true`, otherwise throws an
   * [[java.lang.UnsupportedOperationException]].
   */
  def unsafeThreadLocal(): ThreadLocal[A] = if (ioLocalPropagation)
    new ThreadLocal[A] {
      override def get(): A = {
        self.getOrDefault(IOLocal.getThreadLocalState())
      }

      override def set(value: A): Unit = {
        IOLocal.setThreadLocalState(self.set(IOLocal.getThreadLocalState(), value))
      }

      override def remove(): Unit = {
        IOLocal.setThreadLocalState(self.reset(IOLocal.getThreadLocalState()))
      }
    }
  else
    throw new UnsupportedOperationException(
      "IOLocal-ThreadLocal propagation is disabled.\n" +
        "Enable by setting cats.effect.ioLocalPropagation=true."
    )

}
