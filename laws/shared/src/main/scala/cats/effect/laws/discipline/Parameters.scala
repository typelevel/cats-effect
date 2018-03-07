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

package cats.effect.laws.discipline

import cats.effect.internals.IOPlatform


/**
 * Parameters that can be used for tweaking how the tests behave.
 *
 * @param stackSafeIterationsCount specifies the number of iterations
 *        necessary in loops meant to prove stack safety; needed
 *        because these tests can be very heavy
 */
final case class Parameters(stackSafeIterationsCount: Int)

object Parameters {
  /** Default parameters. */
  implicit val default: Parameters =
    Parameters(stackSafeIterationsCount = {
      if (IOPlatform.isJVM) 100000 else 100
    })
}
