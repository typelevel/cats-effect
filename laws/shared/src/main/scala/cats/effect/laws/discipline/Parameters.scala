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

package cats.effect.laws.discipline

import cats.effect.internals.IOPlatform

/**
 * Parameters that can be used for tweaking how the tests behave.
 *
 * @param stackSafeIterationsCount specifies the number of iterations
 *        necessary in loops meant to prove stack safety; needed
 *        because these tests can be very heavy
 *
 * @param allowNonTerminationLaws specifies if the laws that detect
 *        non-termination (e.g. `IO.never`) should be enabled or not.
 *        Default is `true`, however if the only way to detect non-termination
 *        is to block the thread with a timeout, then that makes tests
 *        really hard to evaluate, so it's best if they are disabled
 */
final case class Parameters(stackSafeIterationsCount: Int, allowNonTerminationLaws: Boolean)

object Parameters {

  /** Default parameters. */
  implicit val default: Parameters =
    Parameters(allowNonTerminationLaws = true, stackSafeIterationsCount = {
      if (IOPlatform.isJVM) 10000 else 100
    })
}
