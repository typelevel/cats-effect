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
import java.util.concurrent.{ForkJoinPool, ForkJoinWorkerThread, ScheduledExecutorService, TimeUnit}
import scala.concurrent.ExecutionContext

private[effect] trait IOAppPlatform { self: IOApp =>
  /**
   * The main method. Runs the `IO` returned by [[run]] and exits the
   * JVM with the resulting code after shutting down the
   * [[executionContextResource]] and the [[ScheduledExecutorService]]
   * resource.
   */
  final def main(args: Array[String]): Unit = {
    val code = IOAppPlatform.main(args, executionContextResource, scheduledExecutorResource)(run(_, _))
    if (code == 0) {
      // Return naturally from main. This allows any non-daemon
      // threads to gracefully complete their work, and managed
      // environments to execute their own shutdown hooks.
      ()
    } else {
      sys.exit(code)
    }
  }

  /**
   * Provides a main execution context for the app. By default, this is a
   * fork-join pool with the same number of threads as available
   * processors. This is used instead of the
   * `scala.concurrent.ExecutionContext.global` in order to support
   * awaiting the graceful termination of any pending tasks.
   *
   * This same resource is used for the [[IOApp.Runtime]]'s execution
   * context, [[ContextShift]], and [[Timer]].
   */
  protected def executionContextResource: Resource[SyncIO, ExecutionContext] =
    Resource.make(SyncIO(
      new ForkJoinPool(
        Runtime.getRuntime.availableProcessors.max(2),
        new ForkJoinPool.ForkJoinWorkerThreadFactory {
          def newThread(pool: ForkJoinPool): ForkJoinWorkerThread = {
            val th = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool)
            th.setName(s"cats-effect-${th.getId}")
            th
          }
        },
        null,
        true)
    ))(pool => SyncIO {
      pool.shutdown()
      pool.awaitTermination(Long.MaxValue, TimeUnit.MILLISECONDS)
    })
      .map(ExecutionContext.fromExecutorService)

  /**
   * Provides a scheduler resource for the app. Defaults to a
   * ScheduledExecutorService with two threads.  This resource is used
   * to construct the runtime's [[Timer]].
   */
  protected def scheduledExecutorResource: Resource[SyncIO, ScheduledExecutorService] =
    Resource.make(SyncIO(IOTimer.newScheduler(daemon = false)))(s => SyncIO {
      s.shutdown()
      s.awaitTermination(Long.MaxValue, TimeUnit.MILLISECONDS)
    })
}

private[effect] object IOAppPlatform {
  def main(args: Array[String],
           executionContextResource: Resource[SyncIO, ExecutionContext],
           scheduledExecutorResource: Resource[SyncIO, ScheduledExecutorService])(
    run: (IOApp.Runtime, List[String]) => IO[ExitCode]
  ): Int = {
    def unsafeAcquire[A](resource: Resource[SyncIO, A]): (A, ExitCase[Throwable] => SyncIO[Unit]) =
      resource match {
        case Resource.Allocate(resource) =>
          resource.unsafeRunSync()
        case Resource.Bind(source, fs) =>
          val (s, shutdownS) = unsafeAcquire(source)
          val (a, shutdownA) = unsafeAcquire(fs(s))
          (a, exitCase => shutdownA(exitCase).guaranteeCase(shutdownS))
        case Resource.Suspend(resource) =>
          unsafeAcquire(resource.unsafeRunSync())
      }

    val (scheduler, shutdownScheduler) = unsafeAcquire(scheduledExecutorResource)
    val (ec, shutdownEc) = unsafeAcquire(executionContextResource)

    val runtime = IOApp.Runtime(ec, scheduler)

    val code = run(runtime, args.toList).redeem(
      e => {
        Logger.reportFailure(e)
        ExitCode.Error.code
      },
      r => r.code
    ).start(runtime.contextShift).flatMap { fiber =>
      installHook(fiber).map(_ => fiber)
    }.flatMap(_.join).unsafeRunSync()

    // Wait for graceful release of scheduler resource
    shutdownScheduler(ExitCase.Completed)
      .handleError(Logger.reportFailure)
      .unsafeRunSync()

    // Wait for graceful release of executor resource
    shutdownEc(ExitCase.Completed)
      .handleError(Logger.reportFailure)
      .unsafeRunSync()

    code
  }

  private def installHook(fiber: Fiber[IO, Int]): IO[Unit] =
    IO {
      sys.addShutdownHook {
        // Should block the thread until all finalizers are executed
        fiber.cancel.unsafeRunSync()
      }
    }

  trait RuntimePlatformCompanion {
    def apply(ec: ExecutionContext, ses: ScheduledExecutorService) = new Runtime {
      implicit def executionContext: ExecutionContext = ec
      implicit def contextShift: ContextShift[IO] = IO.contextShift(ec)
      implicit def timer: Timer[IO] = IO.timer(ec, ses)
    }
  }
}
