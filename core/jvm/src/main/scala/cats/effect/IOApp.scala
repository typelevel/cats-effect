/*
 * Copyright 2020 Typelevel
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

import java.util.concurrent.CountDownLatch

trait IOApp {

  def run(args: List[String]): IO[ExitCode]

  protected val runtime: unsafe.IORuntime = unsafe.IORuntime.global

  final def main(args: Array[String]): Unit = {

    val rt = Runtime.getRuntime()

    val latch = new CountDownLatch(1)
    @volatile var error: Throwable = null
    @volatile var result: ExitCode = null

    val ioa = run(args.toList)

    val fiber =
      ioa.unsafeRunFiber(
        {
          error = new CancellationException("IOApp main fiber was canceled")
          latch.countDown()
        },
        { t =>
          error = t
          latch.countDown()
        },
        { a =>
          result = a
          latch.countDown()
        })(runtime)

    def handleShutdown(): Unit = {
      if (latch.getCount() > 0) {
        val cancelLatch = new CountDownLatch(1)
        fiber.cancel.unsafeRunAsync(_ => cancelLatch.countDown())(runtime)
        cancelLatch.await()
      }

      // Clean up after ourselves, relevant for running IOApps in sbt,
      // otherwise scheduler threads will accumulate over time.
      runtime.internalShutdown()
      runtime.shutdown()
    }

    val hook = new Thread(() => handleShutdown())
    hook.setName("io-cancel-hook")

    rt.addShutdownHook(hook)

    try {
      latch.await()
      if (error != null) {
        // Runtime has already been shutdown in IOFiber.
        throw error
      } else {
        // Clean up after ourselves, relevant for running IOApps in sbt,
        // otherwise scheduler threads will accumulate over time.
        runtime.internalShutdown()
        runtime.shutdown()
        System.exit(result.code)
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
