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

package fiber // Get out of CE package b/c trace filtering

import cats.effect.BaseSpec
import cats.effect.IO

import scala.concurrent.duration._

class IOFiberSpec extends BaseSpec {

  "IOFiber" should {

    "toString a running fiber" in real {
      def loop: IO[Unit] = IO.unit >> loop
      val pattern =
        raw"cats.effect.IOFiber@[0-9a-f][0-9a-f]+ RUNNING >> @ fiber.IOFiberSpec.loop\$$1\(IOFiberSpec.scala:[0-9]{2}\)"
      for {
        f <- loop.start
        s <- IO(f.toString)
        _ <- IO.println(s)
        _ <- f.cancel
      } yield s.matches(pattern)
    }

    "toString a suspended fiber" in real {
      val pattern =
        raw"cats.effect.IOFiber@[0-9a-f]+ SUSPENDED"
      for {
        f <- IO.sleep(1.hour).start
        _ <- IO.sleep(1.milli)
        s <- IO(f.toString)
        _ <- IO.println(s)
        _ <- f.cancel
      } yield s.matches(pattern)
    }

  }

}
