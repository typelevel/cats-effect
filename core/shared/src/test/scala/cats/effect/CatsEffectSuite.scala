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

import munit.{DisciplineSuite, Location}

/**
 * A stopgap solution until upstream dependencies (cats, cats-laws and discipline-munit for dotty Scala.js) are
 * officially released for dotty (without resorting to `withDottyCompat`). Currently, `cats-effect` transitively depends
 * on a compat version of `munit` (even though it is directly released for dotty) and because of that, the correct
 * dotty macro cannot be inferred that injects `munit.Location` instances into assertions. When all dependencies are
 * released for dotty, this trait can be dropped outright and test classes can depend directly on `munit.FunSuite` or
 * `munit.DisciplineSuite`, as needed.
 */
trait CatsEffectSuite extends DisciplineSuite {
  implicit protected val munitLocation: Location = implicitly
}
