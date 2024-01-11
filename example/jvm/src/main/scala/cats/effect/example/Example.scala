/*
 * Copyright 2020-2023 Typelevel
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
package example

import cats.effect.std._

import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicReference

object Example extends IOApp.Simple {

  def run: IO[Unit] = {
    val x = new AtomicReference("")
    Dispatcher.sequential[IO].use { dispatcher =>
      IO(
        (1 to 25).toList.foreach { i =>
          dispatcher.unsafeRunAndForget(IO {
            x.accumulateAndGet(s", ${i.toString}", _ + _)
          })
        }
      )
    } >> IO.sleep(10.millis) >> IO.println(x)
  }
}
