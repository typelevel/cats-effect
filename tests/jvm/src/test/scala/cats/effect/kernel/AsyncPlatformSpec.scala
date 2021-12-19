/*
 * Copyright 2020-2021 Typelevel
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

package cats.effect.kernel

import cats.effect.BaseSpec
import cats.effect.IO

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

class AsyncPlatformSpec extends BaseSpec {

  "AsyncPlatform CompletableFuture conversion" should {
    "cancel CompletableFuture on fiber cancelation" in realWithRuntime { implicit r =>
      for {
        gate <- IO.deferred[Unit]
        cf <- IO {
          CompletableFuture.supplyAsync { () =>
            gate.complete(()).unsafeRunSync()
            Thread.sleep(1000) // some computation
          }
        }
        fiber <- IO.fromCompletableFuture(IO.pure(cf)).start
        _ <- gate.get // wait for the callback to be set-up
        _ <- fiber.cancel
      } yield cf.join() must throwA[CancellationException]
    }
  }
}
