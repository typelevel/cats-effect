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

package cats.effect.unsafe

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.scalanative.meta.LinktimeInfo

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private[unsafe] abstract class IORuntimeCompanionPlatform { this: IORuntime.type =>

  private[this] final val DefaultBlockerPrefix = "io-compute-blocker"

  def createWorkStealingComputeThreadPool(
      threads: Int = Math.max(2, Runtime.getRuntime().availableProcessors()),
      threadPrefix: String = "io-compute",
      blockerThreadPrefix: String = DefaultBlockerPrefix,
      runtimeBlockingExpiration: Duration = 60.seconds,
      reportFailure: Throwable => Unit = _.printStackTrace(),
      blockedThreadDetectionEnabled: Boolean = false,
      shutdownTimeout: Duration = 1.second,
      pollingSystem: PollingSystem = createDefaultPollingSystem(),
      uncaughtExceptionHandler: Thread.UncaughtExceptionHandler = (_, ex) =>
        ex.printStackTrace()
  ): (ExecutionContextExecutor with Scheduler, pollingSystem.Api, () => Unit) = {

    val threadPool =
      new WorkStealingThreadPool[pollingSystem.Poller](
        threads,
        threadPrefix,
        blockerThreadPrefix,
        runtimeBlockingExpiration,
        blockedThreadDetectionEnabled && (threads > 1),
        shutdownTimeout,
        pollingSystem,
        reportFailure,
        uncaughtExceptionHandler
      )

    (threadPool, pollingSystem.makeApi(threadPool), { () => threadPool.shutdown() })
  }

  def createDefaultScheduler(threadPrefix: String = "io-scheduler"): (Scheduler, () => Unit) =
    Scheduler.createDefaultScheduler(threadPrefix)

  def createDefaultPollingSystem(): PollingSystem = {
    if (LinktimeInfo.isLinux)
      EpollSystem
    else if (LinktimeInfo.isMac)
      KqueueSystem
    else
      SleepSystem
  }

  def createDefaultBlockingExecutionContext(
      threadPrefix: String = "io-blocking"
  ): (ExecutionContext, () => Unit) =
    createDefaultBlockingExecutionContext(threadPrefix, _.printStackTrace())

  private[effect] def createDefaultBlockingExecutionContext(
      threadPrefix: String,
      reportFailure: Throwable => Unit
  ): (ExecutionContext, () => Unit) = {
    val threadCount = new AtomicInteger(0)
    val executor = Executors.newCachedThreadPool { (r: Runnable) =>
      val t = new Thread(r)
      t.setName(s"${threadPrefix}-${threadCount.getAndIncrement()}")
      t.setDaemon(true)
      t
    }
    (ExecutionContext.fromExecutor(executor, reportFailure), { () => executor.shutdown() })
  }

  @volatile private[this] var _global: IORuntime = null

  private[effect] def installGlobal(global: => IORuntime): Boolean = {
    if (_global == null) {
      _global = global
      true
    } else {
      false
    }
  }

  private[effect] def resetGlobal(): Unit =
    _global = null

  def global: IORuntime = {
    if (_global == null) {
      installGlobal {
        val (compute, poller, computeDown) = createWorkStealingComputeThreadPool()
        val (blocking, blockingDown) = createDefaultBlockingExecutionContext()
        val shutdown = () => {
          computeDown()
          blockingDown()
          resetGlobal()
        }

        IORuntime(compute, blocking, compute, List(poller), shutdown, IORuntimeConfig())
      }
      ()
    }

    _global
  }

  private[effect] def registerFiberMonitorMBean(fiberMonitor: FiberMonitor): () => Unit = {
    val _ = fiberMonitor
    () => ()
  }
}
