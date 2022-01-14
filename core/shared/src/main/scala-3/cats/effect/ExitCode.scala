/*
 * Copyright 2020-2022 Typelevel
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

/**
 * Represents the exit code of an application.
 *
 * `code` is constrained to a range from 0 to 255, inclusive.
 */
// The strange encoding of this class is due to https://github.com/lampepfl/dotty/issues/14240
sealed class ExitCode private[effect] (val code: Int) extends Product, Equals, Serializable {

  def _1: Int = code

  def canEqual(that: Any): Boolean = that.isInstanceOf[ExitCode]

  override def equals(that: Any): Boolean = that match {
    case ExitCode(code) => this.code == code
    case _ => false
  }

  def productArity: Int = 1

  def productElement(n: Int): Any = if (n == 0) code else throw new NoSuchElementException

  override def hashCode = code

  override def toString = s"ExitCode($code)"
}

object ExitCode {

  /**
   * Creates an `ExitCode`.
   *
   * @param i
   *   the value whose 8 least significant bits are used to construct an exit code within the
   *   valid range.
   */
  def apply(i: Int): ExitCode = new ExitCode(i & 0xff)

  def unapply(ec: ExitCode): ExitCode = ec

  val Success: ExitCode = ExitCode(0)
  val Error: ExitCode = ExitCode(1)
}
