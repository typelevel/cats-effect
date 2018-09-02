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

import scala.concurrent.ExecutionContext

private[effect] object IOAppPlatform {

  def main(args: Array[String], executionResource: Resource[IO, ExecutionContext])(run: (List[String], ExecutionContext) => IO[ExitCode]): Unit = {
    val code = executionResource.use { implicit ec =>
      mainFiber(args)(run).flatMap(_.join)
    }.unsafeRunSync()
    if (code == 0) {
      // Return naturally from main. This allows any non-daemon
      // threads to gracefully complete their work, and managed
      // environments to execute their own shutdown hooks.
      ()
    } else {
      sys.exit(code)
    }
  }

  def mainFiber(args: Array[String])(run: (List[String], ExecutionContext) => IO[ExitCode])(implicit ec: ExecutionContext): IO[Fiber[IO, Int]] = {
    val io = run(args.toList, ec).redeem(
      e => {
        Logger.reportFailure(e)
        ExitCode.Error.code
      },
      r => r.code)

    io.start.flatMap { fiber =>
      installHook(fiber).map(_ => fiber)
    }
  }

  private def installHook(fiber: Fiber[IO, Int]): IO[Unit] =
    IO {
      sys.addShutdownHook {
        // Should block the thread until all finalizers are executed
        fiber.cancel.unsafeRunSync()
      }
    }
}
