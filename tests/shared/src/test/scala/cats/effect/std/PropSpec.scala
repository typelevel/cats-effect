/*
 * Copyright 2020-2023 Typelevel
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

class PropSpec extends BaseSpec {

  "Prop" should {
    "retrieve a property just set" in real {
      Random.javaUtilConcurrentThreadLocalRandom[IO].nextString(12).flatMap { key =>
        Prop[IO].set(key, "bar") *> Prop[IO].get(key).flatMap(x => IO(x mustEqual Some("bar")))
      }
    }
    "return none for a non-existent property" in real {
      Prop[IO].get("MADE_THIS_UP").flatMap(x => IO(x must beNone))
    }
    "unset" in real {
      Random.javaUtilConcurrentThreadLocalRandom[IO].nextString(12).flatMap { key =>
        Prop[IO].set(key, "bar") *> Prop[IO]
          .unset(key) *> Prop[IO].get(key).flatMap(x => IO(x must beNone))
      }
    }
    "retrieve the system properties" in real {
      for {
        _ <- Prop[IO].set("some property", "the value")
        props <- Prop[IO].entries
        assertion <- IO(props mustEqual System.getProperties())
      } yield assertion
    }
  }
}
