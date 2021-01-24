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

import scala.concurrent.{Await, CancellationException, Promise}
import scala.concurrent.duration._

trait IOApp {

  def run(args: List[String]): IO[ExitCode]

  protected val runtime: unsafe.IORuntime = unsafe.IORuntime.global

  final def main(args: Array[String]): Unit = {

    val rt = Runtime.getRuntime()

    val latch = Promise[Unit]()
    @volatile var error: Throwable = null
    @volatile var result: ExitCode = null

    val ioa = run(args.toList)

    val fiber =
      ioa.unsafeRunFiber(
        {
          error = new CancellationException("IOApp main fiber was canceled")
          latch.success(())
        },
        { t =>
          error = t
          latch.success(())
        },
        { a =>
          result = a
          latch.success(())
        })(runtime)

    def handleShutdown(): Unit = {
      if (!latch.isCompleted) {
        val cancelLatch = Promise[Unit]()
        fiber.cancel.unsafeRunAsync(_ => cancelLatch.success(()))(runtime)
        Await.result(cancelLatch.future, Duration.Inf)
      }

      // Clean up after ourselves, relevant for running IOApps in sbt,
      // otherwise scheduler threads will accumulate over time.
      runtime.internalShutdown()
      runtime.shutdown()
    }

    val hook = new Thread(() => handleShutdown())
    hook.setName("io-cancel-hook")

    try {
      rt.addShutdownHook(hook)
    } catch {
      case _: IllegalStateException =>
        // we're already being shut down
        handleShutdown()
    }

    try {
      Await.result(latch.future, Duration.Inf)
      if (error != null) {
        // Runtime has already been shutdown in IOFiber.
        throw error
      } else {
        // Clean up after ourselves, relevant for running IOApps in sbt,
        // otherwise scheduler threads will accumulate over time.
        runtime.internalShutdown()
        runtime.shutdown()
        if (result == ExitCode.Success) {
          // Return naturally from main. This allows any non-daemon
          // threads to gracefully complete their work, and managed
          // environments to execute their own shutdown hooks.
          ()
        } else {
          System.exit(result.code)
        }
      }
    } catch {
      // this handles sbt when fork := false
      case _: InterruptedException =>
        hook.start()
        rt.removeShutdownHook(hook)
        Thread.currentThread().interrupt()
    }
  }
}

object IOApp {

  trait Simple extends IOApp {
    def run: IO[Unit]
    final def run(args: List[String]): IO[ExitCode] = run.as(ExitCode.Success)
  }

}
