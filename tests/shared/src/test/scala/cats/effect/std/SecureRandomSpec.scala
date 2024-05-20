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

class SecureRandomSpec extends BaseSpec {

  "SecureRandom" should {
    "securely generate random bytes" in real {
      for {
        random1 <- SecureRandom.javaSecuritySecureRandom[IO]
        bytes1 <- random1.nextBytes(128)
        random2 <- SecureRandom.javaSecuritySecureRandom[IO](2)
        bytes2 <- random2.nextBytes(256)
        bytes3 <- random2.nextBytes(1024)
      } yield bytes1.length == 128 && bytes2.length == 256 && bytes3.length == 1024
    }

    "overrides nextInt" in real {
      for {
        secureRandom <- SecureRandom.javaSecuritySecureRandom[IO]
        secureInts <- secureRandom.nextInt.replicateA(3)
        insecureRandom <- Random.scalaUtilRandomSeedInt[IO](0)
        insecureInts <- insecureRandom.nextInt.replicateA(3)
      } yield secureInts != insecureInts
    }
  }

}
