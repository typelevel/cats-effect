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

package not.cats.effect // verifies scoping outside of CE

import cats.{Align, CommutativeApplicative}
import cats.effect.{BaseSuite, IO}
import cats.syntax.all._

class IOParImplicitSuite extends BaseSuite {

  test("Can resolve CommutativeApplicative instance") {
    List(1, 2, 3).parUnorderedTraverse(_ => IO.unit) // compilation test
    true
  }

  test("Can resolve IO.Par instances") { // compilation test
    Align[IO.Par]
    CommutativeApplicative[IO.Par]
    true
  }

}
