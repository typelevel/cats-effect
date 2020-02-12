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

import cats.Eval
import cats.effect.{ContextShift, ExitCode, IO, Resource, Timer}

private[effect] object IOResourceAppPlatform {
  def main(args: Array[String], contextShift: Eval[ContextShift[IO]], timer: Eval[Timer[IO]])(
    run: List[String] => Resource[IO, ExitCode]
  ): Unit = {
    val code: Int = mainProcess(args, contextShift, timer)(run).unsafeRunSync().code
    if (code == 0) {
      // Return naturally from main. This allows any non-daemon
      // threads to gracefully complete their work, and managed
      // environments to execute their own shutdown hooks.
      ()
    } else {
      sys.exit(code)
    }
  }

  def mainProcess(args: Array[String], cs: Eval[ContextShift[IO]], timer: Eval[Timer[IO]])(
    run: List[String] => Resource[IO, ExitCode]
  ): IO[ExitCode] = {
    val _ = cs.hashCode() + timer.hashCode()
    run(args.toList).allocated
      .flatMap {
        case (exitCode, shutdownAction) =>
          installHook(shutdownAction).map(_ => exitCode)
      }
      .handleErrorWith { e =>
        IO {
          Logger.reportFailure(e)
          ExitCode.Error
        }
      }
  }

  private def installHook(shutdownAction: IO[Unit]): IO[Unit] =
    IO {
      sys.addShutdownHook {
        // Should block the thread until all finalizers are executed
        shutdownAction.unsafeRunSync()
      }
      ()
    }
}
