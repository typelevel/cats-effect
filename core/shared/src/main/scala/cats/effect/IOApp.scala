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

import cats.effect.internals.IOAppPlatform
import scala.concurrent.ExecutionContext

/**
 * `App` type that runs a [[cats.effect.IO]].  Shutdown occurs after
 * the `IO` completes, as follows:
 *
 * - If completed with `ExitCode.Success`, the main method exits and
 *   shutdown is handled by the platform.
 *
 * - If completed with any other [[ExitCode]], `sys.exit` is called
 *   with the specified code.
 *
 * - If the `IO` raises an error, the stack trace is printed to
 *   standard error and `sys.exit(1)` is called.
 *
 * When a shutdown is requested via a signal, the `IO` is canceled and
 * we wait for the `IO` to release any resources.  The process exits
 * with the numeric value of the signal plus 128.
 *
 * {{{
 * import cats.effect._
 * import cats.implicits._
 *
 * object MyApp extends IOApp {
 *   def run(runtime: IOApp.Runtime, args: List[String]): IO[ExitCode] =
 *     args.headOption match {
 *       case Some(name) =>
 *         IO(println(s"Hello, \${name}.")).as(ExitCode.Success)
 *       case None =>
 *         IO(System.err.println("Usage: MyApp name")).as(ExitCode(2))
 *     }
 * }
 * }}}
 */
trait IOApp extends IOAppPlatform {
  /**
   * Produces the `IO` to be run as an app.
   *
   * @param runtime the [[Runtime]] for this app
   *
   * @param args the command line args passed to the application
   *
   * @return an [[cats.effect.ExitCode]] to exit the process with
   * after the `ec` completes.
   */
  def run(runtime: IOApp.Runtime, args: List[String]): IO[ExitCode]
}

object IOApp {
  /**
   * A runtime for an [[IOApp]] in [[IO]]. Import from the runtime to get an
   * implicit execution context, [[ContextShift]], and [[Timer]].
   **/
  trait Runtime {
    /**
     * The execution context for an [[IOApp]].  This is the main thread pool.
     *
     * Blocking on this thread pool is heavily discouraged.  Use
     * [[ContextShift#evalOn]] with a separate pool for these cases.
     */
    implicit def executionContext: ExecutionContext

    /**
     * A [[ContextShift]] for the [[IOApp]] that shifts back to
     * [[executionContext]].
     */
    implicit def contextShift: ContextShift[IO]

    /**
     * A [[Timer]] for the [[IOApp]] that shifts back to
     * [[executionContext]].
     */
    implicit def timer: Timer[IO]
  }

  object Runtime extends IOAppPlatform.RuntimePlatformCompanion {
    def apply(ec: ExecutionContext) = new Runtime {
      implicit def executionContext: ExecutionContext = ec
      implicit def contextShift: ContextShift[IO] = IO.contextShift(ec)
      implicit def timer: Timer[IO] = IO.timer(ec)
    }
  }
}
