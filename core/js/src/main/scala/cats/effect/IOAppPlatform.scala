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

package cats.effect

import scala.concurrent.CancellationException
import scala.concurrent.duration._
import scala.scalajs.js

private[effect] abstract class IOAppPlatform { this: IOApp =>

  private[this] var _runtime: unsafe.IORuntime = null

  /**
   * The runtime which will be used by `IOApp` to evaluate the
   * [[IO]] produced by the `run` method. This may be overridden
   * by `IOApp` implementations which have extremely specialized
   * needs, but this is highly unlikely to ever be truly needed.
   * As an example, if an application wishes to make use of an
   * alternative compute thread pool (such as `Executors.fixedThreadPool`),
   * it is almost always better to leverage [[IO.evalOn]] on the value
   * produced by the `run` method, rather than directly overriding
   * `runtime`.
   *
   * In other words, this method is made available to users, but its
   * use is strongly discouraged in favor of other, more precise
   * solutions to specific use-cases.
   *
   * This value is guaranteed to be equal to [[unsafe.IORuntime.global]].
   */
  protected def runtime: unsafe.IORuntime = _runtime

  /**
   * The configuration used to initialize the [[runtime]] which will
   * evaluate the [[IO]] produced by `run`. It is very unlikely that
   * users will need to override this method.
   */
  protected def runtimeConfig: unsafe.IORuntimeConfig = unsafe.IORuntimeConfig()

  final def main(args: Array[String]): Unit = {
    if (runtime == null) {
      import unsafe.IORuntime

      IORuntime installGlobal {
        IORuntime(
          IORuntime.defaultComputeExecutionContext,
          IORuntime.defaultComputeExecutionContext,
          IORuntime.defaultScheduler,
          () => (),
          runtimeConfig)
      }

      _runtime = IORuntime.global
    }

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
          IO.raiseError(new CancellationException("IOApp main fiber was canceled"))
        case Left(Outcome.Errored(t)) => IO.raiseError(t)
        case Left(Outcome.Succeeded(code)) => code
        case Right(Outcome.Errored(t)) => IO.raiseError(t)
        case Right(_) => sys.error("impossible")
      }
      .unsafeRunAsync({
        case Left(t) =>
          t match {
            case _: CancellationException =>
              // Do not report cancelation exceptions but still exit with an error code.
              reportExitCode(ExitCode(1))
            case t: Throwable =>
              throw t
          }
        case Right(code) => reportExitCode(code)
      })(runtime)
  }

  private[this] def reportExitCode(code: ExitCode): Unit =
    if (js.typeOf(js.Dynamic.global.process) != "undefined") {
      js.Dynamic.global.process.exitCode = code.code
    }
}
