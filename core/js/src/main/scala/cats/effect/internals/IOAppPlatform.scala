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
import scala.concurrent.ExecutionContext
import scala.scalajs.js

private[effect] trait IOAppPlatform { self: IOApp =>
  /**
   * The main method. Runs the `IO` returned by [[run]] and exits the
   * JVM with the resulting code after shutting down the
   * [[executionContextResource]] and the [[ScheduledExecutorService]]
   * resource.
   */
  def main(args: Array[String]): Unit = {
    IOAppPlatform.main(args, executionContext)(run(_, _)).unsafeRunAsync {
      case Left(t) =>
        Logger.reportFailure(t)
        IOAppPlatform.exit(ExitCode.Error.code)
      case Right(0) =>
        ()
      case Right(code) =>
        IOAppPlatform.exit(code)
    }
  }

  /**
   * The execution context for the app. Defaults to the global execution
   * context.
   *
   * Unlike [[IOApp]] on the JVM, this execution context is not a
   * [[Resource]], as the standard execution on Scala.js runtime can't
   * be shut down. However, the process does not terminate until the
   * event queue is processed, which supports a similar graceful shutdown
   * to the JVM equivalent.
   */
  protected def executionContext: ExecutionContext =
    ExecutionContext.global
}

private[effect] object IOAppPlatform {
  private def exit(code: Int) = {
    // Try to exit gracefully using `process.exitCode = code`
    try js.Dynamic.global.process.exitCode = code
    catch {
      // If that's not supported in our env, do it the aggressive way
      case _: js.JavaScriptException => sys.exit(code)
    }
  }

  def main(args: Array[String], executionContext: ExecutionContext)(
    run: (IOApp.Runtime, List[String]) => IO[ExitCode]
  ): IO[Int] = {
    val runtime = IOApp.Runtime(executionContext)

    // An infinite heartbeat to keep main alive.  This is similar to
    // `IO.never`, except `IO.never` doesn't schedule any tasks and is
    // insufficient to keep main alive.  The tick is fast enough that
    // it isn't silently discarded, as longer ticks are, but slow
    // enough that we don't interrupt often.  1 hour was chosen
    // empirically.
    def keepAlive: IO[Nothing] = runtime.timer.sleep(1.hour) >> keepAlive

    val program = run(runtime, args.toList).handleErrorWith {
      t => IO(Logger.reportFailure(t)) *> IO.pure(ExitCode.Error)
    }

    IO.race(keepAlive, program)(runtime.contextShift).flatMap {
      case Left(_) =>
        // This case is unreachable, but scalac won't let us omit it.
        IO.raiseError(new AssertionError("IOApp keep alive failed unexpectedly."))
      case Right(exitCode) =>
        IO.pure(exitCode.code)
    }.start(runtime.contextShift).flatMap { fiber =>
      installHandler(fiber) *> fiber.join
    }
  }

  val defaultTimer: Timer[IO] = IOTimer.global
  val defaultContextShift: ContextShift[IO] = IOContextShift.global

  private def installHandler(fiber: Fiber[IO, Int]): IO[Unit] = {
    def handler(code: Int) = () =>
      fiber.cancel.unsafeRunAsync { result =>
        result.swap.foreach(Logger.reportFailure)
        IO(sys.exit(code + 128))
      }

    IO {
      if (!js.isUndefined(js.Dynamic.global.process)) {
        val process = js.Dynamic.global.process
        process.on("SIGHUP", handler(1))
        process.on("SIGINT", handler(2))
        process.on("SIGTERM", handler(15))
      }
    }
  }

  trait RuntimePlatformCompanion
}
