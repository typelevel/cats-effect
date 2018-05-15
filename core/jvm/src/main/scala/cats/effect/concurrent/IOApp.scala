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

/** `App` type that runs a [[cats.effect.IO]] and exits with the
  * returned code.  If the `IO` raises an error, then the stack trace
  * is printed to standard error and the JVM exits with code 1.  
  * 
  * When a shutdown is requested via a signal, the `IO` is canceled
  * and we wait for the `IO` to release any resources.  The JVM exits
  * with the numeric value of the signal plus 128.
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
  def run(args: List[String]): IO[ExitCode]

  /** The main method that runs the `IO` returned by [[run]]
    * and exits the JVM with the resulting code on completion.
    */
  final def main(args: Array[String]): Unit = {
    object Canceled extends RuntimeException

    val program = for {
      done <- Deferred[IO, Unit]
      fiber <- run(args.toList)
        .onCancelRaiseError(Canceled) // force termination on cancel
        .handleErrorWith {
          case Canceled =>
            // This error will get overridden by the JVM's signal
            // handler to 128 plus the signal.  We don't have
            // access to the signal, so we have to provide a dummy
            // value here.
            IO.pure(ExitCode.Error)
          case t =>
            IO(Logger.reportFailure(t)).as(ExitCode.Error)
        }
        .productL(done.complete(()))
        .start
      _ <- IO(sys.addShutdownHook {
        fiber.cancel.unsafeRunSync()
        done.get.unsafeRunSync()
      })
      exitCode <- fiber.join
    } yield exitCode.intValue

    sys.exit(program.unsafeRunSync())
  }
}
