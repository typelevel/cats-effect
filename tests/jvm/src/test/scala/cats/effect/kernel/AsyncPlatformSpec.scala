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

import cats.effect.{BaseSpec, IO}

import scala.concurrent.duration._

import java.util.concurrent.{CancellationException, CompletableFuture}

class AsyncPlatformSpec extends BaseSpec {

  val smallDelay: IO[Unit] = IO.sleep(1.second)

  "AsyncPlatform CompletableFuture conversion" should {
    "cancel CompletableFuture on fiber cancellation" in real {
      lazy val cf = CompletableFuture.supplyAsync { () =>
        Thread.sleep(2000) // some computation
      }

      for {
        fiber <- IO.fromCompletableFuture(IO(cf)).start
        _ <- smallDelay // time for the callback to be set-up
        _ <- fiber.cancel
        _ <- IO(cf.join() must throwA[CancellationException])
      } yield ok
    }
  }
}
