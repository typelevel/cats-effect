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

class SystemPropertiesSuite extends BaseSuite {

  real("retrieve a property just set") {
    Random.javaUtilConcurrentThreadLocalRandom[IO].nextString(12).flatMap { key =>
      SystemProperties[IO].set(key, "bar") *>
        SystemProperties[IO].get(key).flatMap(x => IO(assertEquals(x, Some("bar"))))
    }
  }
  real("return none for a non-existent property") {
    SystemProperties[IO].get("MADE_THIS_UP").flatMap(x => IO(assertEquals(x, None)))
  }
  real("clear") {
    Random.javaUtilConcurrentThreadLocalRandom[IO].nextString(12).flatMap { key =>
      SystemProperties[IO].set(key, "bar") *> SystemProperties[IO].clear(key) *>
        SystemProperties[IO].get(key).flatMap(x => IO(assertEquals(x, None)))
    }
  }

}
