/*
 * Copyright 2020 Typelevel
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

class UnsafeRunSpec extends BaseSpec {

  "unsafe run" should {
    "be able to execute a synchronous IO" in real {
      var i = 42

      val io = IO {
        i += 1
        i
      }

      val (future, _) = runtime().unsafeRunForIO.unsafeRunFutureCancelable(io)
      IO.fromFuture(IO(future)).map(_ must beEqualTo(43))
    }

    "be able to execute an asynchronous IO" in real {
      val io = IO.async_[Int](cb => cb(Right(42)))

      val (future, _) = runtime().unsafeRunForIO.unsafeRunFutureCancelable(io)
      IO.fromFuture(IO(future)).map(_ must beEqualTo(42))
    }

    "be able to cancel the execution of an IO" in real {
      var canceled = false

      val io = IO
        .never
        .onCancel(IO {
          canceled = true
        })

      val (_, canceler) = runtime().unsafeRunForIO.unsafeRunFutureCancelable(io)
      val future = canceler()
      IO.fromFuture(IO(future))
        .map(_ => { val capture = canceled; capture must beEqualTo(true) })
    }
  }
}
