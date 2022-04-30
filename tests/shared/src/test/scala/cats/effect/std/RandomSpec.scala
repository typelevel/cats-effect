/*
 * Copyright 2020-2022 Typelevel
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

class RandomSpec extends BaseSpec {

  "Random" should {
    "securely generate random bytes" in real {
      for {
        random1 <- Random.javaSecuritySecureRandom[IO]
        bytes1 <- random1.nextBytes(128)
        random2 <- Random.javaSecuritySecureRandom[IO](2)
        bytes2 <- random2.nextBytes(256)
      } yield bytes1.length == 128 && bytes2.length == 256
    }

    "prevent array reference from leaking in ThreadLocalRandom.nextBytes impl" in real {
      val random = Random.javaUtilConcurrentThreadLocalRandom[IO]
      val nextBytes = random.nextBytes(128)
      for {
        bytes1 <- nextBytes
        bytes2 <- nextBytes
      } yield bytes1 ne bytes2
    }

    "prevent array reference from leaking in ScalaRandom.nextBytes impl" in real {
      for {
        random <- Random.scalaUtilRandom[IO]
        nextBytes = random.nextBytes(128)
        bytes1 <- nextBytes
        bytes2 <- nextBytes
      } yield bytes1 ne bytes2
    }

  }

}
