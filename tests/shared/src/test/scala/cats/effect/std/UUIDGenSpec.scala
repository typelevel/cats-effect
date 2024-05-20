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

class UUIDGenSpec extends BaseSpec {

  "UUIDGen" should {
    "securely generate UUIDs" in real {
      for {
        left <- UUIDGen.randomUUID[IO]
        right <- UUIDGen.randomUUID[IO]
      } yield left != right
    }
    "use the correct variant and version" in real {
      for {
        uuid <- UUIDGen.randomUUID[IO]
      } yield (uuid.variant should be_==(2)) and (uuid.version should be_==(4))
    }
  }

}
