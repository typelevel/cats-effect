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
package unsafe

class IOLocalsSuite extends BaseSuite {

  real("return a default value") {
    IOLocal(42).flatMap(local => IO(local.unsafeThreadLocal().get())).map(assertEquals(_, 42))
  }

  real("return a set value") {
    for {
      local <- IOLocal(42)
      threadLocal <- IO(local.unsafeThreadLocal())
      _ <- local.set(24)
      got <- IO(threadLocal.get())
    } yield assertEquals(got, 24)
  }

  real("unsafely set") {
    IOLocal(42).flatMap(local =>
      IO(local.unsafeThreadLocal().set(24)) *> local.get.map(assertEquals(_, 24)))
  }

  real("unsafely reset") {
    for {
      local <- IOLocal(42)
      threadLocal <- IO(local.unsafeThreadLocal())
      _ <- local.set(24)
      _ <- IO(threadLocal.remove())
      got <- local.get
    } yield assertEquals(got, 42)
  }

}
