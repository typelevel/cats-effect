/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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
import scala.scalajs.js

private[effect] object IOResourceAppPlatform {
  def main(args: Array[String], cs: Eval[ContextShift[IO]], timer: Eval[Timer[IO]])(
    run: List[String] => Resource[IO, ExitCode]
  ): Unit =
    mainProcess(args, cs, timer)(run).unsafeRunAsync {
      case Left(t) =>
        Logger.reportFailure(t)
        setExitCode(ExitCode.Error.code)
      case Right(code) =>
        setExitCode(code.code)
    }

  def mainProcess(args: Array[String], cs: Eval[ContextShift[IO]], timer: Eval[Timer[IO]])(
    run: List[String] => Resource[IO, ExitCode]
  ): IO[ExitCode] =
    run(args.toList)
      .evalMap(exitCode => runKeepAlive(cs, timer)(IO(exitCode)))
      .allocated
      .flatMap {
        case (exitCode, shutdownAction) =>
          installHandler(shutdownAction).as(exitCode)
      }
      .handleErrorWith { e =>
        IO {
          Logger.reportFailure(e)
          ExitCode.Error
        }
      }

  /**
   * Sets the exit code with `process.exitCode = code` for runtimes
   * that support it.  This allows a graceful shutdown with a specific
   * exit code.
   *
   * If the call is not supported, does a `sys.exit(code)` on any
   * non-zero exit code.
   *
   * @see https://nodejs.org/api/process.html#process_process_exitcode
   **/
  private def setExitCode(code: Int): Unit =
    try js.Dynamic.global.process.exitCode = code
    catch {
      case _: js.JavaScriptException =>
        if (code != 0) sys.exit(code)
    }

  def runKeepAlive(contextShift: Eval[ContextShift[IO]], timer: Eval[Timer[IO]]): IO[ExitCode] => IO[ExitCode] =
    process => {
      // An infinite heartbeat to keep main alive.  This is similar to
      // `IO.never`, except `IO.never` doesn't schedule any tasks and is
      // insufficient to keep main alive.  The tick is fast enough that
      // it isn't silently discarded, as longer ticks are, but slow
      // enough that we don't interrupt often.  1 hour was chosen
      // empirically.
      def keepAlive: IO[Nothing] = timer.value.sleep(1.hour) >> keepAlive

      val program = process.handleErrorWith { t =>
        IO(Logger.reportFailure(t)) *> IO.pure(ExitCode.Error)
      }

      IO.race(keepAlive, program)(contextShift.value)
        .flatMap {
          case Left(_) =>
            // This case is unreachable, but scalac won't let us omit it.
            IO.raiseError(new AssertionError("IOApp keep alive failed unexpectedly."))
          case Right(exitCode) =>
            IO.pure(exitCode)
        }
    }

  val defaultTimer: Timer[IO] = IOTimer.global
  val defaultContextShift: ContextShift[IO] = IOContextShift.global

  private def installHandler(shutdownAction: IO[Unit]): IO[Unit] = {
    def handler(code: Int) =
      () =>
        shutdownAction.unsafeRunAsync { result =>
          result.swap.foreach(Logger.reportFailure)
          sys.exit(code + 128)
      }

    IO {
      if (!js.isUndefined(js.Dynamic.global.process)) {
        val process = js.Dynamic.global.process
        process.on("SIGHUP", handler(1))
        process.on("SIGINT", handler(2))
        process.on("SIGTERM", handler(15))
        ()
      }
    }
  }
}
