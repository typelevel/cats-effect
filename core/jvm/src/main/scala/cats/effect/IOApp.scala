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

import java.util.concurrent.CountDownLatch

trait IOApp {

  def run(args: List[String]): IO[Int]

  protected val runtime: unsafe.IORuntime = unsafe.IORuntime.global

  protected implicit val unsafeRunForIO: unsafe.UnsafeRun[IO] = runtime.unsafeRunForIO

  final def main(args: Array[String]): Unit = {
    val rt = Runtime.getRuntime()

    val latch = new CountDownLatch(1)
    @volatile var results: Either[Throwable, Int] = null

    val ioa = run(args.toList)

    val fiber = ioa.unsafeRunFiber(true) { e =>
      results = e
      latch.countDown()
    }(runtime)

    def handleShutdown(): Unit = {
      if (latch.getCount() > 0) {
        val cancelLatch = new CountDownLatch(1)
        fiber.cancel.unsafeRunAsync(_ => cancelLatch.countDown())(runtime)
        cancelLatch.await()
      }

      runtime.shutdown()
    }

    val hook = new Thread(() => handleShutdown())
    hook.setName("io-cancel-hook")

    rt.addShutdownHook(hook)

    try {
      latch.await()

      results.fold(throw _, System.exit(_))
    } catch {
      // this handles sbt when fork := false
      case _: InterruptedException =>
        hook.start()
        rt.removeShutdownHook(hook)
        Thread.currentThread().interrupt()
    }
  }
}
