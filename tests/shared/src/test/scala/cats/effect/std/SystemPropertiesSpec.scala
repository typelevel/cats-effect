/*
 * Copyright 2020-2024 Typelevel
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

import org.typelevel.scalaccompat.annotation._

class SystemPropertiesSpec extends BaseSpec {

  "SystemProperties" should {
    "retrieve a property just set" in real {
      Random.javaUtilConcurrentThreadLocalRandom[IO].nextString(12).flatMap { key =>
        SystemProperties[IO].set(key, "bar") *>
          SystemProperties[IO].get(key).flatMap(x => IO(x mustEqual Some("bar")))
      }
    }
    "return none for a non-existent property" in real {
      SystemProperties[IO].get("MADE_THIS_UP").flatMap(x => IO(x must beNone))
    }
    "unset" in real {
      Random.javaUtilConcurrentThreadLocalRandom[IO].nextString(12).flatMap { key =>
        SystemProperties[IO].set(key, "bar") *> SystemProperties[IO].unset(key) *>
          SystemProperties[IO].get(key).flatMap(x => IO(x must beNone))
      }
    }
    "retrieve the system properties" in real {
      for {
        _ <- SystemProperties[IO].set("some property", "the value")
        props <- SystemProperties[IO].entries
        expected <- IO {
          import scala.collection.JavaConverters._
          Map.empty ++ System.getProperties.asScala
        }: @nowarn213("cat=deprecation") @nowarn3("cat=deprecation")
        assertion <- IO(props mustEqual expected)
      } yield assertion
    }
  }
}
