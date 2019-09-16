/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats
package effect

import javax.security.auth.Destroyable

class ResourceJVMTests extends BaseTestsSuite {
  test("resource from Destroyable is auto destroyed") {
    var destroyed = false
    val destroyable = new Destroyable {
      override def destroy(): Unit = destroyed = true
    }

    val result = Resource
      .fromDestroyable(IO(destroyable))
      .use(_ => IO.pure("Hello world"))
      .unsafeRunSync()

    result shouldBe "Hello world"
    destroyed shouldBe true
  }
}
