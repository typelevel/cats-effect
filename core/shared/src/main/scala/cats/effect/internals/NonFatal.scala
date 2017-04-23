/*
 * Copyright 2017 Typelevel
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

package cats.effect.internals

/**
 * Extractor of non-fatal `Throwable` instances.
 *
 * Alternative to [[scala.util.control.NonFatal]] that only
 * considers `VirtualMachineError`s as fatal.
 *
 * Inspired by the FS2 implementation.
 */
private[effect] object NonFatal {
  def apply(t: Throwable): Boolean = t match {
    case _: VirtualMachineError => false
    case _ => true
  }

  def unapply(t: Throwable): Option[Throwable] =
    if (apply(t)) Some(t) else None
}
