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

package fiber // Get out of CE package b/c trace filtering

import cats.effect.{BaseSpec, DetectPlatform, IO}

import scala.concurrent.duration._

class IOFiberSpec extends BaseSpec with DetectPlatform {

  "IOFiber" should {
    if (!isJS || !isWSL) {
      "toString a running fiber" in real {
        def loop: IO[Unit] = IO.unit.flatMap(_ => loop)
        val pattern =
          raw"cats.effect.IOFiber@[0-9a-f][0-9a-f]+ RUNNING(: flatMap @ fiber.IOFiberSpec.loop\$$[0-9]\(((.*IOFiberSpec.scala:[0-9]{2})|(Unknown Source))\))?"
        for {
          f <- loop.start
          _ <- IO.sleep(1.milli)
          s <- IO(f.toString)
          // _ <- IO.println(s)
          _ <- f.cancel
          _ <- IO(s must beMatching(pattern))
        } yield ok
      }

      "toString a suspended fiber" in real {
        // separate method to have it in the trace:
        def foreverNever =
          IO.async[Unit](_ => IO.pure(Some(IO.unit)))
        val pattern =
          raw"cats.effect.IOFiber@[0-9a-f][0-9a-f]+ SUSPENDED(: async @ fiber.IOFiberSpec.foreverNever\$$[0-9]\(((.*IOFiberSpec.scala:[0-9]{2})|(Unknown Source))\))?"
        for {
          f <- foreverNever.start
          _ <- IO.sleep(100.milli)
          s <- IO(f.toString)
          _ <- f.cancel
          _ <- IO(s must beMatching(pattern))
        } yield ok
      }
    } else {
      "toString a running fiber" in skipped("Scala.js exception unmangling is buggy on WSL")
      "toString a suspended fiber" in skipped("Scala.js exception unmangling is buggy on WSL")
    }

    "toString a completed fiber" in real {
      val pattern = raw"cats.effect.IOFiber@[0-9a-f][0-9a-f]+ COMPLETED"
      for {
        f <- IO.unit.start
        _ <- f.joinWithNever
        _ <- IO(f.toString must beMatching(pattern))
      } yield ok
    }
  }
}
