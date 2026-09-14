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

import cats.effect.unsafe.{IORuntime, UnsafeNonFatal}

import scala.concurrent.ExecutionContext

trait IOAppPlatform extends IOAppCommon with IOAppMultiThreaded {
  this: IOApp =>

  private[effect] def defaultMainThread: ExecutionContext = {
    new ExecutionContext {
      def reportFailure(t: Throwable): Unit =
        t match {
          case t if UnsafeNonFatal(t) =>
            IOAppPlatform.this.reportFailure(t).unsafeRunAndForgetWithoutCallback()(runtime)

          case t =>
            handleTerminalFailure(t)
        }

      def execute(r: Runnable): Unit =
        if (!queue.offer(r)) {
          runtime.blocking.execute(() => queue.put(r))
        }
    }
  }

  private[effect] def defaultGlobalRuntime: IORuntime = {
    val (compute, poller, compDown) =
      IORuntime.createWorkStealingComputeThreadPool(
        threads = computeWorkerThreadCount,
        reportFailure = t => reportFailure(t).unsafeRunAndForgetWithoutCallback()(runtime),
        blockedThreadDetectionEnabled = false, // TODO
        pollingSystem = pollingSystem
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
}
