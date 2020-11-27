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

import scala.concurrent.duration._
import scala.scalajs.js

trait IOApp {

  def run(args: List[String]): IO[ExitCode]

  protected val runtime: unsafe.IORuntime = unsafe.IORuntime.global

  final def main(args: Array[String]): Unit = {
    // An infinite heartbeat to keep main alive.  This is similar to
    // `IO.never`, except `IO.never` doesn't schedule any tasks and is
    // insufficient to keep main alive.  The tick is fast enough that
    // it isn't silently discarded, as longer ticks are, but slow
    // enough that we don't interrupt often.  1 hour was chosen
    // empirically.
    lazy val keepAlive: IO[Nothing] =
      IO.sleep(1.hour) >> keepAlive

    val argList =
      if (js.typeOf(js.Dynamic.global.process) != "undefined" && js.typeOf(
          js.Dynamic.global.process.argv) != "undefined")
        js.Dynamic.global.process.argv.asInstanceOf[js.Array[String]].toList.drop(2)
      else
        args.toList

    Spawn[IO]
      .raceOutcome[ExitCode, Nothing](run(argList), keepAlive)
      .flatMap {
        case Left(Outcome.Canceled()) =>
          IO.raiseError(new RuntimeException("IOApp main fiber canceled"))
        case Left(Outcome.Errored(t)) => IO.raiseError(t)
        case Left(Outcome.Succeeded(code)) => code
        case Right(Outcome.Errored(t)) => IO.raiseError(t)
        case Right(_) => sys.error("impossible")
      }
      .unsafeRunAsync({
        case Left(t) => throw t
        case Right(code) => reportExitCode(code)
      })(unsafe.IORuntime.global)
  }

  private[this] def reportExitCode(code: ExitCode): Unit =
    if (js.typeOf(js.Dynamic.global.process) != "undefined") {
      js.Dynamic.global.process.exitCode = code.code
    }
}

object IOApp {

  trait Simple extends IOApp {
    def run: IO[Unit]
    final def run(args: List[String]): IO[ExitCode] = run.as(ExitCode.Success)
  }

}
