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
      Prop[IO]
        .set("foo", "bar") *> Prop[IO].get("foo").flatMap(x => IO(x mustEqual Some("bar")))
    }
    "return none for a non-existent property" in real {
      Prop[IO].get("MADE_THIS_UP").flatMap(x => IO(x must beNone))
    }
    "getAndSet" in real {
      for {
        _ <- Prop[IO].set("foo", "bar")
        getAndSetResult <- Prop[IO].getAndSet("foo", "baz")
        getResult <- Prop[IO].get("foo")
      } yield {
        getAndSetResult mustEqual Some("bar")
        getResult mustEqual Some("baz")
      }
    }
    "getAndUpdate" in real {
      for {
        _ <- Prop[IO].set("foo", "bar")
        getAndSetResult <- Prop[IO].getAndUpdate("foo", v => v + "baz")
        getResult <- Prop[IO].get("foo")
      } yield {
        getAndSetResult mustEqual Some("bar")
        getResult mustEqual Some("barbaz")
      }
    }
    "unset" in real {
      Prop[IO].set("foo", "bar") *> Prop[IO]
        .unset("foo") *> Prop[IO].get("foo").flatMap(x => IO(x must beNone))
    }
    "not modify anythng if a value for the key does not exist" in real {
      Prop[IO].unset("foo") *> Prop[IO].get("foo").flatMap(x => IO(x must beNone)) *> Prop[IO]
        .modify("foo", _ => ("new value", "output"))
        .flatMap(x => IO(x must beNone))
    }
  }
}
