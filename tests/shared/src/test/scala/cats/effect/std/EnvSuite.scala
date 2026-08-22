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
package std

class EnvSuite extends BaseSuite {

  real("retrieve a variable from the environment") {
    Env[IO].get("HOME").flatMap(x => IO(assert(x.isDefined)))
  }
  real("return none for non-existent environment variable") {
    Env[IO].get("MADE_THIS_UP").flatMap(x => IO(assert(x.isEmpty)))
  }
  real("provide an iterable of all the things") {
    Env[IO].entries.flatMap(x => IO(assert(x.nonEmpty)))
  }

}
