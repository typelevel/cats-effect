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

import scala.concurrent.{blocking, CancellationException}

import java.util.concurrent.CountDownLatch

private[effect] abstract class IOAppPlatform { this: IOApp =>

  private[this] var _runtime: unsafe.IORuntime = null

  /**
   * The runtime which will be used by `IOApp` to evaluate the
   * [[IO]] produced by the `run` method. This may be overridden
   * by `IOApp` implementations which have extremely specialized
   * needs, but this is highly unlikely to ever be truly needed.
   * As an example, if an application wishes to make use of an
   * alternative compute thread pool (such as `Executors.fixedThreadPool`),
   * it is almost always better to leverage [[IO.evalOn]] on the value
   * produced by the `run` method, rather than directly overriding
   * `runtime`.
   *
   * In other words, this method is made available to users, but its
   * use is strongly discouraged in favor of other, more precise
   * solutions to specific use-cases.
   *
   * This value is guaranteed to be equal to [[unsafe.IORuntime.global]].
   */
  protected def runtime: unsafe.IORuntime = _runtime

  /**
   * The configuration used to initialize the [[runtime]] which will
   * evaluate the [[IO]] produced by `run`. It is very unlikely that
   * users will need to override this method.
   */
  protected def runtimeConfig: unsafe.IORuntimeConfig = unsafe.IORuntimeConfig()

  /**
   * Controls the number of worker threads which will be allocated to
   * the compute pool in the underlying runtime. In general, this should be
   * no ''greater'' than the number of physical threads made available by
   * the underlying kernel (which can be determined using
   * `Runtime.getRuntime().availableProcessors()`). For any application
   * which has significant additional non-compute thread utilization (such
   * as asynchronous I/O worker threads), it may be optimal to reduce the
   * number of compute threads by the corresponding amount such that the
   * total number of active threads exactly matches the number of underlying
   * physical threads.
   *
   * In practice, tuning this parameter is unlikely to affect your application
   * performance beyond a few percentage points, and the default value is
   * optimal (or close to optimal) in ''most'' common scenarios.
   *
   * '''This setting is JVM-specific and will not compile on JavaScript.'''
   *
   * For more details on Cats Effect's runtime threading model please see
   * [[https://typelevel.org/cats-effect/docs/thread-model]].
   */
  protected def computeWorkerThreadCount: Int =
    Math.max(2, Runtime.getRuntime().availableProcessors())

  final def main(args: Array[String]): Unit = {
    if (runtime == null) {
      import unsafe.IORuntime

      IORuntime installGlobal {
        val (compute, compDown) =
          IORuntime.createDefaultComputeThreadPool(runtime, threads = computeWorkerThreadCount)

        val (blocking, blockDown) =
          IORuntime.createDefaultBlockingExecutionContext()

        val (scheduler, schedDown) =
          IORuntime.createDefaultScheduler()

        IORuntime(
          compute,
          blocking,
          scheduler,
          { () =>
            compDown()
            blockDown()
            schedDown()
          },
          runtimeConfig)
      }

      _runtime = IORuntime.global
    }

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
        blocking(cancelLatch.await())
      }

      // Clean up after ourselves, relevant for running IOApps in sbt,
      // otherwise scheduler threads will accumulate over time.
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
      blocking(latch.await())
      error match {
        case null =>
          // Clean up after ourselves, relevant for running IOApps in sbt,
          // otherwise scheduler threads will accumulate over time.
          runtime.shutdown()
          if (result == ExitCode.Success) {
            // Return naturally from main. This allows any non-daemon
            // threads to gracefully complete their work, and managed
            // environments to execute their own shutdown hooks.
            if (NonDaemonThreadLogger.isEnabled())
              new NonDaemonThreadLogger().start()
            else
              ()
          } else {
            System.exit(result.code)
          }
        case _: CancellationException =>
          // Do not report cancelation exceptions but still exit with an error code.
          System.exit(1)
        case t: Throwable =>
          throw t
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
