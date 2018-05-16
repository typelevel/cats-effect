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
import java.util.concurrent.CountDownLatch

private[effect] object IOAppPlatform {
  def main(args: Array[String], timer: Timer[IO])(run: List[String] => IO[ExitCode]): Unit = {
    val code = mainFiber(args, timer)(run).flatMap(_.join)
      .handleErrorWith(t => IO(Logger.reportFailure(t)) *> IO(ExitCode.Error.code))
      .unsafeRunSync()
    sys.exit(code)
  }

  def mainFiber(args: Array[String], timer: Timer[IO])(run: List[String] => IO[ExitCode]): IO[Fiber[IO, Int]] = {
    val _ = timer

    object Canceled extends RuntimeException
    for {
      latch <- IO(new CountDownLatch(1))
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
        .productL(IO(latch.countDown()))
        .map(_.code)
        .start
      _ <- IO(sys.addShutdownHook {
        fiber.cancel.unsafeRunSync()
        latch.await()
      })
    } yield fiber
  }

  def defaultTimer: Timer[IO] = IOTimerRef.defaultIOTimer
}
