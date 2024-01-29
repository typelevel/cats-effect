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

/**
 * Represents the exit code of an application.
 *
 * `code` is constrained to a range from 0 to 255, inclusive.
 */
sealed abstract case class ExitCode private (code: Int)

object ExitCode {

  /**
   * Creates an `ExitCode`.
   *
   * @param i
   *   the value whose 8 least significant bits are used to construct an exit code within the
   *   valid range.
   */
  def apply(i: Int): ExitCode = new ExitCode(i & 0xff) {}

  val Success: ExitCode = ExitCode(0)
  val Error: ExitCode = ExitCode(1)
}
