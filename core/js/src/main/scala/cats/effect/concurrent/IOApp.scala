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
package concurrent

import cats.effect.internals.Logger
import cats.implicits._
import scala.scalajs.js.annotation.JSExport
import scala.concurrent.duration._

/** `App` type that runs a [[cats.effect.IO]] and exits with the
  * returned code.  If the `IO` raises an error, then the stack trace
  * is printed to standard error and the process exits with code 1.
  * 
  * When a shutdown is requested via a signal, the process exits
  * without requesting cancellation of the `IO`.  This is different
  * from the JVM, which supports a shutdown hook.
  * 
  * {{{
  * import cats.effect.IO
  * import cats.effect.concurrent.{ExitCode, IOApp}
  * import cats.implicits._
  * 
  * object MyApp extends IOApp {
  *   def run(args: List[String]): IO[ExitCode] =
  *     args.headOption match {
  *       case Some(name) =>
  *         IO(println(s"Hello, $name.")).as(ExitCode.Success)
  *       case None =>
  *         IO(System.err.println("Usage: MyApp name")).as(ExitCode(2))
  *     }
  * }
  * }}}
  */
trait IOApp {
  /** Produces the `IO` to be run as an app.
    * 
    * @return the [[ExitCode]] the JVM exits with
    */
  @JSExport
  def run(args: List[String]): IO[ExitCode]

  /** The main method that runs the `IO` returned by [[run]]
    * and exits the JVM with the resulting code on completion.
    */
  @JSExport
  final def main(args: Array[String]): Unit = {
    // 1. Registers a task
    // 2. But not too slow, or it gets ignored
    // 3. But not too fast, or we needlessly interrupt
    def keepAlive: IO[Unit] = Timer[IO].sleep(1.hour) >> keepAlive

    IO.race(keepAlive, run(args.toList)).runAsync {
      case Left(t) =>
        IO(Logger.reportFailure(t)) *>
        IO(sys.exit(ExitCode.Error.intValue))
      case Right(Left(())) =>
        IO(System.err.println("IOApp keep alive failed unexpectedly.")) *>
        IO(sys.exit(ExitCode.Error.intValue))
      case Right(Right(exitCode)) =>
        IO(sys.exit(exitCode.intValue))
    }.unsafeRunSync()
  }
}
