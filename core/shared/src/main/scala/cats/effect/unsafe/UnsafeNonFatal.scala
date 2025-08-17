/*
 * Copyright 2020-2025 Typelevel
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

import scala.util.control.ControlThrowable

import Platform.static

/**
 * An alternative to [[scala.util.control.NonFatal]] that does not treat
 * [[java.lang.InterruptedException]] as fatal. This is intended for the exclusive use of the
 * Cats-Effect runtime. It is not recommended to treat interrupts as non-fatal in application
 * code, as handling interrupts gracefully is the responsibility of the runtime, so
 * [[UnsafeNonFatal]] should only be used in the fiber runtime.
 */
private[effect] object UnsafeNonFatal {

  /**
   * Returns true if the provided `Throwable` is to be considered non-fatal, or false if it is
   * to be considered fatal
   */
  @static def apply(t: Throwable): Boolean = t match {
    case _: VirtualMachineError | _: ThreadDeath | _: LinkageError | _: ControlThrowable =>
      false
    case _ => true
  }
}

private[effect] class UnsafeNonFatal
