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
  def main(args: Array[String], timer: Eval[Timer[IO]])(run: List[String] => IO[ExitCode]): Unit =
    mainFiber(args, timer)(run).flatMap(_.join).runAsync {
      case Left(t) =>
        IO(Logger.reportFailure(t)) *>
        IO(sys.exit(ExitCode.Error.code))
      case Right(code) =>
        IO(sys.exit(code))
    }.unsafeRunSync()

  def mainFiber(args: Array[String], timer: Eval[Timer[IO]])(run: List[String] => IO[ExitCode]): IO[Fiber[IO, Int]] = {
    // An infinite heartbeat to keep main alive.  This is similar to
    // `IO.never`, except `IO.never` doesn't schedule any tasks and is
    // insufficient to keep main alive.  The tick is fast enough that
    // it isn't silently discarded, as longer ticks are, but slow
    // enough that we don't interrupt often.  1 hour was chosen
    // empirically.
    def keepAlive: IO[Nothing] = timer.value.sleep(1.hour) >> keepAlive

    val program = run(args.toList).handleErrorWith {
      t => IO(Logger.reportFailure(t)) *> IO.pure(ExitCode.Error)
    }

    IO.race(keepAlive, program).flatMap {
      case Left(_) =>
        // This case is unreachable, but scalac won't let us omit it.
        IO.raiseError(new AssertionError("IOApp keep alive failed unexpectedly."))
      case Right(exitCode) =>
        IO.pure(exitCode.code)
    }.start(timer.value)
  }

  val defaultTimer: Timer[IO] = IOTimer.global
}
