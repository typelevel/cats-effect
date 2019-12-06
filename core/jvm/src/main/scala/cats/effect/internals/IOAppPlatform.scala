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

private[effect] object IOAppPlatform {
  def main(args: Array[String], contextShift: Eval[ContextShift[IO]], timer: Eval[Timer[IO]])(
    run: List[String] => IO[ExitCode]
  ): Unit = {
    val code = mainFiber(args, contextShift, timer)(run).flatMap(_.join).unsafeRunSync()
    if (code == 0) {
      // Return naturally from main. This allows any non-daemon
      // threads to gracefully complete their work, and managed
      // environments to execute their own shutdown hooks.
      ()
    } else {
      sys.exit(code)
    }
  }

  def mainFiber(args: Array[String], contextShift: Eval[ContextShift[IO]], timer: Eval[Timer[IO]])(
    run: List[String] => IO[ExitCode]
  ): IO[Fiber[IO, Int]] = {
    val _ = timer // is used on Scala.js
    val io = run(args.toList).redeem(e => {
      Logger.reportFailure(e)
      ExitCode.Error.code
    }, r => r.code)

    io.start(contextShift.value).flatMap { fiber =>
      installHook(fiber).map(_ => fiber)
    }
  }

  // both lazily initiated on JVM platform to prevent
  // warm-up of underlying default EC's for code that does not require concurrency
  def defaultTimer: Timer[IO] =
    IOTimer(PoolUtils.ioAppGlobal)

  def defaultContextShift: ContextShift[IO] =
    IOContextShift(PoolUtils.ioAppGlobal)

  private def installHook(fiber: Fiber[IO, Int]): IO[Unit] =
    IO {
      sys.addShutdownHook {
        // Should block the thread until all finalizers are executed
        fiber.cancel.unsafeRunSync()
      }
      ()
    }
}
