/*
 * Copyright 2020-2025 Typelevel
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

import cats.effect.metrics.{CpuStarvationWarningMetrics, JvmCpuStarvationMetrics}
import cats.effect.std.Console
import cats.effect.tracing.TracingConstants._
import cats.effect.unsafe.UnsafeNonFatal
import cats.syntax.all._

import scala.concurrent.{blocking, CancellationException, ExecutionContext}
import scala.concurrent.duration._

import java.util.concurrent.{ArrayBlockingQueue, CountDownLatch}
import java.util.concurrent.atomic.AtomicInteger

/**
 * The primary entry point to a Cats Effect application. Extend this trait rather than defining
 * your own `main` method. This avoids the need to run [[IO.unsafeRunSync]] (or similar) on your
 * own.
 *
 * `IOApp` takes care of the messy details of properly setting up (and tearing down) the
 * [[unsafe.IORuntime]] needed to run the [[IO]] which represents your application. All of the
 * associated thread pools (if relevant) will be configured with the assumption that your
 * application is fully contained within the `IO` produced by the [[run]] method. Note that the
 * exact details of how the runtime will be configured are very platform-specific. Part of the
 * point of `IOApp` is to insulate users from the details of the underlying runtime (whether JVM
 * or JavaScript).
 *
 * {{{
 *   object MyApplication extends IOApp {
 *     def run(args: List[String]) =
 *       for {
 *         _ <- IO.print("Enter your name: ")
 *         name <- IO.readln
 *         _ <- IO.println("Hello, " + name)
 *       } yield ExitCode.Success
 *   }
 * }}}
 *
 * In the above example, `MyApplication` will be a runnable class with a `main` method, visible
 * to Sbt, IntelliJ, or plain-old `java`. When run externally, it will print, read, and print in
 * the obvious way, producing a final process exit code of 0. Any exceptions thrown within the
 * `IO` will be printed to standard error and the exit code will be set to 1. In the event that
 * the main [[Fiber]] (represented by the `IO` returned by `run`) is canceled, the runtime will
 * produce an exit code of 1.
 *
 * Note that exit codes are an implementation-specific feature of the underlying runtime, as are
 * process arguments. Naturally, all JVMs support these functions, as does Node.js and Scala
 * Native, but some JavaScript execution environments will be unable to replicate these features
 * (or they simply may not make sense). In such cases, exit codes may be ignored and/or argument
 * lists may be empty.
 *
 * Note that in the case of the above example, we would actually be better off using
 * [[IOApp.Simple]] rather than `IOApp` directly, since we are neither using `args` nor are we
 * explicitly producing a custom [[ExitCode]]:
 *
 * {{{
 *   object MyApplication extends IOApp.Simple {
 *     val run =
 *       for {
 *         _ <- IO.print("Enter your name: ")
 *         name <- IO.readln
 *         _ <- IO.println(s"Hello, " + name)
 *       } yield ()
 *   }
 * }}}
 *
 * It is valid to define `val run` rather than `def run` because `IO`'s evaluation is lazy: it
 * will only run when the `main` method is invoked by the runtime.
 *
 * In the event that the process receives an interrupt signal (`SIGINT`) due to Ctrl-C (or any
 * other mechanism), it will immediately `cancel` the main fiber. Assuming this fiber is not
 * within an `uncancelable` region, this will result in interrupting any current activities and
 * immediately invoking any finalizers (see: [[IO.onCancel]] and [[IO.bracket]]). The process
 * will not shut down until the finalizers have completed. For example:
 *
 * {{{
 *   object InterruptExample extends IOApp.Simple {
 *     val run =
 *       IO.bracket(startServer)(
 *         _ => IO.never)(
 *         server => IO.println("shutting down") *> server.close)
 *   }
 * }}}
 *
 * If we assume the `startServer` function has type `IO[Server]` (or similar), this kind of
 * pattern is very common. When this process receives a `SIGINT`, it will immediately print
 * "shutting down" and run the `server.close` effect.
 *
 * One consequence of this design is it is possible to build applications which will ignore
 * process interrupts. For example, if `server.close` runs forever, the process will ignore
 * interrupts and will need to be cleaned up using `SIGKILL` (i.e. `kill -9`). This same
 * phenomenon can be demonstrated by using [[IO.uncancelable]] to suppress all interruption
 * within the application itself:
 *
 * {{{
 *   object Zombie extends IOApp.Simple {
 *     val run = IO.never.uncancelable
 *   }
 * }}}
 *
 * The above process will run forever and ignore all interrupts. The only way it will shut down
 * is if it receives `SIGKILL`.
 *
 * It is possible (though not necessary) to override various platform-specific runtime
 * configuration options, such as `computeWorkerThreadCount` (which only exists on the JVM).
 * Please note that the default configurations have been extensively benchmarked and are optimal
 * (or close to it) in most conventional scenarios.
 *
 * However, with that said, there really is no substitute to benchmarking your own application.
 * Every application and scenario is unique, and you will always get the absolute best results
 * by performing your own tuning rather than trusting someone else's defaults. `IOApp`'s
 * defaults are very ''good'', but they are not perfect in all cases. One common example of this
 * is applications which maintain network or file I/O worker threads which are under heavy load
 * in steady-state operations. In such a performance profile, it is usually better to reduce the
 * number of compute worker threads to "make room" for the I/O workers, such that they all sum
 * to the number of physical threads exposed by the kernel.
 *
 * @see
 *   [[IO]]
 * @see
 *   [[run]]
 * @see
 *   [[ResourceApp]]
 * @see
 *   [[IOApp.Simple]]
 */
trait IOApp {

  private[this] var _runtime: unsafe.IORuntime = null

  /**
   * The runtime which will be used by `IOApp` to evaluate the [[IO]] produced by the `run`
   * method. This may be overridden by `IOApp` implementations which have extremely specialized
   * needs, but this is highly unlikely to ever be truly needed. As an example, if an
   * application wishes to make use of an alternative compute thread pool (such as
   * `Executors.fixedThreadPool`), it is almost always better to leverage [[IO.evalOn]] on the
   * value produced by the `run` method, rather than directly overriding `runtime`.
   *
   * In other words, this method is made available to users, but its use is strongly discouraged
   * in favor of other, more precise solutions to specific use-cases.
   *
   * This value is guaranteed to be equal to [[unsafe.IORuntime.global]].
   */
  protected def runtime: unsafe.IORuntime = _runtime

  /**
   * The configuration used to initialize the [[runtime]] which will evaluate the [[IO]]
   * produced by `run`. It is very unlikely that users will need to override this method.
   */
  protected def runtimeConfig: unsafe.IORuntimeConfig = unsafe.IORuntimeConfig()

  protected def pollingSystem: unsafe.PollingSystem =
    unsafe.IORuntime.createDefaultPollingSystem()

  /**
   * Controls the number of worker threads which will be allocated to the compute pool in the
   * underlying runtime. In general, this should be no ''greater'' than the number of physical
   * threads made available by the underlying kernel (which can be determined using
   * `Runtime.getRuntime().availableProcessors()`). For any application which has significant
   * additional non-compute thread utilization (such as asynchronous I/O worker threads), it may
   * be optimal to reduce the number of compute threads by the corresponding amount such that
   * the total number of active threads exactly matches the number of underlying physical
   * threads.
   *
   * In practice, tuning this parameter is unlikely to affect your application performance
   * beyond a few percentage points, and the default value is optimal (or close to optimal) in
   * ''most'' common scenarios.
   *
   * '''This setting is JVM-specific and will not compile on JavaScript.'''
   *
   * For more details on Cats Effect's runtime threading model please see
   * [[https://typelevel.org/cats-effect/docs/thread-model]].
   */
  protected def computeWorkerThreadCount: Int =
    Math.max(2, Runtime.getRuntime().availableProcessors())

  // arbitrary constant is arbitrary
  private[this] lazy val queue = new ArrayBlockingQueue[AnyRef](32)

  private[this] def handleTerminalFailure(t: Throwable): Unit = {
    queue.clear()
    queue.put(t)
  }

  /**
   * Executes the provided actions on the JVM's `main` thread. Note that this is, by definition,
   * a single-threaded executor, and should not be used for anything which requires a meaningful
   * amount of performance. Additionally, and also by definition, this process conflicts with
   * producing the results of an application. If one fiber calls `evalOn(MainThread)` while the
   * main fiber is returning, the first one will "win" and will cause the second one to wait its
   * turn. Once the main fiber produces results (or errors, or cancels), any remaining enqueued
   * actions are ignored and discarded (a mostly irrelevant issue since the process is, at that
   * point, terminating).
   *
   * This is ''not'' recommended for use in most applications, and is really only appropriate
   * for scenarios where some third-party library is sensitive to the exact identity of the
   * calling thread (for example, LWJGL). In these scenarios, it is recommended that the
   * absolute minimum possible amount of work is handed off to the main thread.
   */
  protected def MainThread: ExecutionContext =
    if (queue eq queue)
      new ExecutionContext {
        def reportFailure(t: Throwable): Unit =
          t match {
            case t if UnsafeNonFatal(t) =>
              IOApp.this.reportFailure(t).unsafeRunAndForgetWithoutCallback()(runtime)

            case t =>
              handleTerminalFailure(t)
          }

        def execute(r: Runnable): Unit =
          if (!queue.offer(r)) {
            runtime.blocking.execute(() => queue.put(r))
          }
      }
    else
      throw new UnsupportedOperationException(
        "Your IOApp's super class has not been recompiled against Cats Effect 3.4.0+."
      )

  /**
   * Configures the action to perform when unhandled errors are caught by the runtime. An
   * unhandled error is an error that is raised (and not handled) on a Fiber that nobody is
   * joining.
   *
   * For example:
   *
   * {{{
   *   import scala.concurrent.duration._
   *   override def run: IO[Unit] = IO(throw new Exception("")).start *> IO.sleep(1.second)
   * }}}
   *
   * In this case, the exception is raised on a Fiber with no listeners. Nobody would be
   * notified about that error. Therefore it is unhandled, and it goes through the reportFailure
   * mechanism.
   *
   * By default, `reportFailure` simply delegates to
   * [[cats.effect.std.Console!.printStackTrace]]. It is safe to perform any `IO` action within
   * this handler; it will not block the progress of the runtime. With that said, some care
   * should be taken to avoid raising unhandled errors as a result of handling unhandled errors,
   * since that will result in the obvious chaos.
   */
  protected def reportFailure(err: Throwable): IO[Unit] =
    Console[IO].printStackTrace(err)

  /**
   * Configures whether to enable blocked thread detection. This is relatively expensive so is
   * off by default and probably not something that you want to permanently enable in
   * production.
   *
   * If enabled, the compute pool will attempt to detect when blocking operations have been
   * erroneously wrapped in `IO.apply` or `IO.delay` instead of `IO.blocking` or
   * `IO.interruptible` and will report stacktraces of this to stderr.
   *
   * This may be of interest if you've been getting warnings about CPU starvation printed to
   * stderr. [[https://typelevel.org/cats-effect/docs/core/starvation-and-tuning]]
   *
   * Can also be configured by setting the `cats.effect.detectBlockedThreads` system property.
   */
  protected def blockedThreadDetectionEnabled: Boolean =
    java.lang.Boolean.getBoolean("cats.effect.detectBlockedThreads") // defaults to disabled

  /**
   * Controls whether non-daemon threads blocking application exit are logged to stderr when the
   * `IO` produced by `run` has completed. This mechanism works by starting a daemon thread
   * which periodically polls all active threads on the system, checking for any remaining
   * non-daemon threads and enumerating them. This can be very useful for determining why your
   * application ''isn't'' gracefully exiting, since the alternative is that the JVM will just
   * hang waiting for the non-daemon threads to terminate themselves. This mechanism will not,
   * by itself, block shutdown in any way. For this reason, it defaults to `true`.
   *
   * In the event that your application exit is being blocked by a non-daemon thread which you
   * cannot control (i.e. a bug in some dependency), you can circumvent the blockage by
   * appending the following to the `IO` returned from `run`:
   *
   * {{{
   *   val program: IO[ExitCode] = ???                      // the original IO returned from `run`
   *   program.guarantee(IO(Runtime.getRuntime().halt(0)))  // the bit you need to add
   * }}}
   *
   * This finalizer will forcibly terminate the JVM (kind of like `kill -9`), ignoring daemon
   * threads ''and'' shutdown hooks, but only after all native Cats Effect finalizers have
   * completed. In most cases, this should be a relatively benign thing to do, though it's
   * definitely a bad default. Only use this to workaround a blocking non-daemon thread that you
   * cannot otherwise influence!
   *
   * Can also be configured by setting the `cats.effect.logNonDaemonThreadsOnExit` system
   * property.
   *
   * @see
   *   [[logNonDaemonThreadsInterval]]
   */
  protected def logNonDaemonThreadsEnabled: Boolean =
    Option(System.getProperty("cats.effect.logNonDaemonThreadsOnExit"))
      .map(_.toLowerCase()) match {
      case Some(value) => value.equalsIgnoreCase("true")
      case None => true // default to enabled
    }

  /**
   * Controls the interval used by the non-daemon thread detector. Defaults to `10.seconds`.
   *
   * Can also be configured by setting the `cats.effect.logNonDaemonThreads.sleepIntervalMillis`
   * system property.
   *
   * @see
   *   [[logNonDaemonThreadsEnabled]]
   */
  protected def logNonDaemonThreadsInterval: FiniteDuration =
    Option(System.getProperty("cats.effect.logNonDaemonThreads.sleepIntervalMillis"))
      .flatMap(time => Either.catchOnly[NumberFormatException](time.toLong.millis).toOption)
      .getOrElse(10.seconds)

  /**
   * Defines what to do when CpuStarvationCheck is triggered. Defaults to log a warning to
   * System.err.
   */
  protected def onCpuStarvationWarn(metrics: CpuStarvationWarningMetrics): IO[Unit] =
    CpuStarvationCheck.logWarning(metrics)

  /**
   * Defines what to do when IOApp detects that `main` is being invoked on a `Thread` which
   * isn't the main process thread. This condition can happen when we are running inside of an
   * `sbt run` with `fork := false`
   */
  def warnOnNonMainThreadDetected: Boolean =
    Option(System.getProperty("cats.effect.warnOnNonMainThreadDetected"))
      .map(_.equalsIgnoreCase("true"))
      .getOrElse(true)

  private def onNonMainThreadDetected(): Unit = {
    if (warnOnNonMainThreadDetected)
      System
        .err
        .println(
          """|[WARNING] IOApp `main` is running on a thread other than the main thread.
             |This may prevent correct resource cleanup after `main` completes.
             |This condition could be caused by executing `run` in an interactive sbt session with `fork := false`.
             |To ensure proper cleanup, either
             |  - set `Compile / run / fork := true` in this project
             |  - use `fgRun` instead of `run`
             |  - use 'bgStop` to terminate `run`
             |  - update sbt to a version after 1.10.5
             |
             |To silence this warning set the system property:
             |`-Dcats.effect.warnOnNonMainThreadDetected=false`.
             |""".stripMargin
        )
    else ()
  }

  /**
   * The entry point for your application. Will be called by the runtime when the process is
   * started. If the underlying runtime supports it, any arguments passed to the process will be
   * made available in the `args` parameter. The numeric value within the resulting [[ExitCode]]
   * will be used as the exit code when the process terminates unless terminated exceptionally
   * or by interrupt.
   *
   * @param args
   *   The arguments passed to the process, if supported by the underlying runtime. For example,
   *   `java com.company.MyApp --foo --bar baz` or `node com-mycompany-fastopt.js --foo --bar
   *   baz` would each result in `List("--foo", "--bar", "baz")`.
   * @see
   *   [[IOApp.Simple!.run:cats\.effect\.IO[Unit]*]]
   */
  def run(args: List[String]): IO[ExitCode]

  final def main(args: Array[String]): Unit = {
    // checked in openjdk 8-17; this attempts to detect when we're running under artificial environments, like sbt
    val isForked = Thread.currentThread().getId() == 1
    if (!isForked) onNonMainThreadDetected()

    val installed = if (runtime == null) {
      import unsafe.IORuntime

      val installed = IORuntime installGlobal {
        val (compute, poller, compDown) =
          IORuntime.createWorkStealingComputeThreadPool(
            threads = computeWorkerThreadCount,
            reportFailure = t => reportFailure(t).unsafeRunAndForgetWithoutCallback()(runtime),
            blockedThreadDetectionEnabled = blockedThreadDetectionEnabled,
            pollingSystem = pollingSystem,
            uncaughtExceptionHandler = (_, t) => handleTerminalFailure(t)
          )

        val (blocking, blockDown) =
          IORuntime.createDefaultBlockingExecutionContext(
            threadPrefix = "io-blocking",
            reportFailure =
              (t: Throwable) => reportFailure(t).unsafeRunAndForgetWithoutCallback()(runtime)
          )

        IORuntime(
          compute,
          blocking,
          compute,
          List(poller),
          { () =>
            compDown()
            blockDown()
            IORuntime.resetGlobal()
          },
          runtimeConfig)
      }

      _runtime = IORuntime.global

      installed
    } else {
      unsafe.IORuntime.installGlobal(runtime)
    }

    if (!installed) {
      System
        .err
        .println(
          "WARNING: Cats Effect global runtime already initialized; custom configurations will be ignored")
    }

    if (isStackTracing) {
      val liveFiberSnapshotSignal = sys
        .props
        .get("os.name")
        .toList
        .map(_.toLowerCase)
        .filterNot(
          _.contains("windows")
        ) // Windows does not support signals user overridable signals
        .flatMap(_ => List("USR1", "INFO"))

      liveFiberSnapshotSignal foreach { name =>
        Signal.handle(name, _ => runtime.fiberMonitor.liveFiberSnapshot(System.err.print(_)))
      }
    }

    val rt = Runtime.getRuntime()
    val counter = new AtomicInteger(1)

    val ioa = run(args.toList)

    // workaround for scala#12692, dotty#16352
    val queue = this.queue

    val fiber =
      JvmCpuStarvationMetrics(runtime.metrics.cpuStarvationSampler)
        .flatMap { _ =>
          CpuStarvationCheck
            .run(runtimeConfig, runtime.metrics.cpuStarvationSampler, onCpuStarvationWarn)
            .background
        }
        .surround(ioa)
        .unsafeRunFiber(
          {
            if (counter.decrementAndGet() == 0) {
              queue.clear()
            }
            queue.put(new CancellationException("IOApp main fiber was canceled"))
          },
          { t =>
            if (counter.decrementAndGet() == 0) {
              queue.clear()
            }
            queue.put(t)
          },
          { a =>
            if (counter.decrementAndGet() == 0) {
              queue.clear()
            }
            queue.put(a)
          }
        )(runtime)

    if (isStackTracing)
      runtime.fiberMonitor.monitorSuspended(fiber)
    else
      ()

    def handleShutdown(): Unit = {
      if (counter.compareAndSet(1, 0)) {
        val cancelLatch = new CountDownLatch(1)
        fiber.cancel.unsafeRunAsync(_ => cancelLatch.countDown())(runtime)

        val timeout = runtimeConfig.shutdownHookTimeout
        if (timeout.isFinite) {
          blocking(cancelLatch.await(timeout.length, timeout.unit))
          ()
        } else {
          blocking(cancelLatch.await())
        }
      }

      // Clean up after ourselves, relevant for running IOApps in sbt,
      // otherwise scheduler threads will accumulate over time.
      if (!isForked) runtime.shutdown()
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
      var done = false

      while (!done) {
        val result = blocking(queue.take())
        result match {
          case ec: ExitCode =>
            // Clean up after ourselves, relevant for running IOApps in sbt,
            // otherwise scheduler threads will accumulate over time.
            if (!isForked) runtime.shutdown()
            if (ec == ExitCode.Success) {
              // Return naturally from main. This allows any non-daemon
              // threads to gracefully complete their work, and managed
              // environments to execute their own shutdown hooks.
              if (isForked && logNonDaemonThreadsEnabled)
                new NonDaemonThreadLogger(logNonDaemonThreadsInterval).start()
              else
                ()
            } else if (isForked) {
              System.exit(ec.code)
            }

            done = true

          case e: CancellationException =>
            if (isForked)
              // Do not report cancelation exceptions but still exit with an error code.
              System.exit(1)
            else
              // if we're unforked, the only way to report cancelation is to throw
              throw e

          case t: Throwable =>
            if (UnsafeNonFatal(t)) {
              if (isForked) {
                t.printStackTrace()
                System.exit(1)
              } else {
                throw t
              }
            } else {
              t.printStackTrace()
              rt.halt(1)
            }

          case r: Runnable =>
            try {
              r.run()
            } catch {
              case t if UnsafeNonFatal(t) =>
                IOApp.this.reportFailure(t).unsafeRunAndForgetWithoutCallback()(runtime)

              case t: Throwable =>
                t.printStackTrace()
                rt.halt(1)
            }

          case _ =>
            throw new IllegalStateException(s"${result.getClass.getName} in MainThread queue")
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

  /**
   * A simplified version of [[IOApp]] for applications which ignore their process arguments and
   * always produces [[ExitCode.Success]] (unless terminated exceptionally or interrupted).
   *
   * @see
   *   [[IOApp]]
   */
  trait Simple extends IOApp {
    def run: IO[Unit]
    final def run(args: List[String]): IO[ExitCode] = run.as(ExitCode.Success)
  }
}
