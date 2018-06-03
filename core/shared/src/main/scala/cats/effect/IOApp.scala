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
 *   def run(args: List[String]): IO[ExitCode] =
 *     args.headOption match {
 *       case Some(name) =>
 *         IO(println(s"Hello, \${name}.")).as(ExitCode.Success)
 *       case None =>
 *         IO(System.err.println("Usage: MyApp name")).as(ExitCode(2))
 *     }
 * }
 * }}}
 */
trait IOApp {
  /**
   * Produces the `IO` to be run as an app.
   *
   * @return the [[cats.effect.ExitCode]] the JVM exits with
   */
  def run(args: List[String]): IO[ExitCode]

  /**
   * The main method that runs the `IO` returned by [[run]] and exits
   * the JVM with the resulting code on completion.
   */
  final def main(args: Array[String]): Unit =
    IOAppPlatform.main(args, Eval.later(timer))(run)

  /**
   * Provides an implicit timer instance for the app.
   * 
   * On the JVM, the default lazily constructed from the global
   * execution context.  Override to avoid instantiating this
   * execution context.
   * 
   * On scala.js, the default is `Timer.global`.
   */
  protected implicit def timer: Timer[IO] = IOAppPlatform.defaultTimer
}
