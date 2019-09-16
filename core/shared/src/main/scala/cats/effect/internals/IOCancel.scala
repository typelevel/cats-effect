/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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
package internals

import cats.effect.IO.ContextSwitch

private[effect] object IOCancel {

  /** Implementation for `IO.uncancelable`. */
  def uncancelable[A](fa: IO[A]): IO[A] =
    ContextSwitch(fa, makeUncancelable, disableUncancelable)

  /** Internal reusable reference. */
  private[this] val makeUncancelable: IOConnection => IOConnection =
    _ => IOConnection.uncancelable
  private[this] val disableUncancelable: (Any, Throwable, IOConnection, IOConnection) => IOConnection =
    (_, _, old, _) => old
}
