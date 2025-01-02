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

class UUIDGenSuite extends BaseSuite {

  real("securely generate UUIDs") {
    for {
      left <- UUIDGen.randomUUID[IO]
      right <- UUIDGen.randomUUID[IO]
    } yield assert(left != right)
  }

  real("use the correct variant and version") {
    for {
      uuid <- UUIDGen.randomUUID[IO]
    } yield {
      assertEquals(uuid.variant, 2)
      assertEquals(uuid.version, 4)
    }
  }

}
