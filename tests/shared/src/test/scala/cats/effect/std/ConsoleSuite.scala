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

class ConsoleSuite extends BaseSuite {

  case class Foo(n: Int, b: Boolean)

  testUnit("select default Show.fromToString (IO)") {
    val _ = (
      IO.print(Foo(1, true)), // compilation test
      IO.println(Foo(1, true)) // compilation test
    )
  }

  testUnit("select default Show.fromToString (Console[IO])") {
    val _ = (
      Console[IO].print(Foo(1, true)), // compilation test
      Console[IO].println(Foo(1, true)), // compilation test
      Console[IO].error(Foo(1, true)), // compilation test
      Console[IO].errorln(Foo(1, true)) // compilation test
    )
  }

}
