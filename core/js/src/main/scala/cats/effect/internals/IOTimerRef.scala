/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

import scala.concurrent.ExecutionContext

/**
 * Internal API — gets mixed-in the `IO` companion object.
 */
private[effect] trait IOTimerRef {
  /**
   * Returns a reusable [[Timer]] instance for [[IO]].
   */
  def timer: Timer[IO] = IOTimer.global

  /**
   * Returns a [[Timer]] instance for [[IO]].
   *
   * @param ec is an execution context that gets used for
   *        evaluating the `sleep` tick
   */
  def timer(ec: ExecutionContext): Timer[IO] = new IOTimer(ec)
}
