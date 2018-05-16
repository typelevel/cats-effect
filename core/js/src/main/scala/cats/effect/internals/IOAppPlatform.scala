/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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
package internals

import cats.implicits._
import scala.concurrent.duration._

private[effect] object IOAppPlatform {
  def mainFiber(args: Array[String])(run: List[String] => IO[ExitCode]): IO[Fiber[IO, Int]] = {
    // 1. Registers a task
    // 2. But not too slow, or it gets ignored
    // 3. But not too fast, or we needlessly interrupt
    def keepAlive: IO[Unit] = Timer[IO].sleep(1.hour) >> keepAlive

    val program = run(args.toList).handleErrorWith {
      t => IO(Logger.reportFailure(t)) *> IO.pure(ExitCode.Error)
    }

    IO.race(keepAlive, program).flatMap {
      case Left(()) =>
        IO.raiseError(new AssertionError("IOApp keep alive failed unexpectedly."))
      case Right(exitCode) =>
        IO.pure(exitCode.code)
    }.start
  }

  val defaultTimer: Timer[IO] = IOTimer.global
}
