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

import cats.effect.tracing.TracingConstants._
import cats.effect.unsafe.metrics._

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration._

import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import javax.management.ObjectName

private[unsafe] abstract class IORuntimeCompanionPlatform { this: IORuntime.type =>

  private[this] final val DefaultBlockerPrefix = "io-compute-blocker"

  @deprecated("Preserved for binary-compatibility", "3.5.0")
  def createWorkStealingComputeThreadPool(
      threads: Int,
      threadPrefix: String,
      blockerThreadPrefix: String,
      runtimeBlockingExpiration: Duration,
      reportFailure: Throwable => Unit
  ): (ExecutionContextExecutor with Scheduler, () => Unit) =
    createWorkStealingComputeThreadPool(
      threads,
      threadPrefix,
      blockerThreadPrefix,
      runtimeBlockingExpiration,
      reportFailure,
      false
    )

  @deprecated("Preserved for binary-compatibility", "3.6.0")
  def createWorkStealingComputeThreadPool(
      threads: Int,
      threadPrefix: String,
      blockerThreadPrefix: String,
      runtimeBlockingExpiration: Duration,
      reportFailure: Throwable => Unit,
      blockedThreadDetectionEnabled: Boolean
  ): (ExecutionContextExecutor with Scheduler, () => Unit) = {
    val (pool, _, shutdown) = createWorkStealingComputeThreadPool(
      threads,
      threadPrefix,
      blockerThreadPrefix,
      runtimeBlockingExpiration,
      reportFailure,
      false,
      1.second,
      SleepSystem
    )
    (pool, shutdown)
  }

  // The default compute thread pool on the JVM is now a work stealing thread pool.
  def createWorkStealingComputeThreadPool(
      threads: Int = Math.max(2, Runtime.getRuntime().availableProcessors()),
      threadPrefix: String = "io-compute",
      blockerThreadPrefix: String = DefaultBlockerPrefix,
      runtimeBlockingExpiration: Duration = 60.seconds,
      reportFailure: Throwable => Unit = _.printStackTrace(),
      blockedThreadDetectionEnabled: Boolean = false,
      shutdownTimeout: Duration = 1.second,
      pollingSystem: PollingSystem = SelectorSystem(),
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

    val unregisterMBeans =
      if (isStackTracing) {
        val mBeanServer =
          try ManagementFactory.getPlatformMBeanServer()
          catch {
            case _: Throwable => null
          }

        if (mBeanServer ne null) {
          val registeredMBeans = mutable.Set.empty[ObjectName]

          val threadPoolId = threadPool.id

          try {
            val computePoolSamplerName = new ObjectName(
              s"cats.effect.unsafe.metrics:type=ComputePoolSampler-$threadPoolId")
            val computePoolSampler = new ComputePoolSampler(threadPool)
            mBeanServer.registerMBean(computePoolSampler, computePoolSamplerName)
            registeredMBeans += computePoolSamplerName
          } catch {
            case _: Throwable =>
          }

          val localQueues = threadPool.localQueues
          var i = 0
          val len = localQueues.length

          while (i < len) {
            val localQueue = localQueues(i)

            try {
              val localQueueSamplerName = new ObjectName(
                s"cats.effect.unsafe.metrics:type=LocalQueueSampler-$threadPoolId-$i")
              val localQueueSampler = new LocalQueueSampler(localQueue)
              mBeanServer.registerMBean(localQueueSampler, localQueueSamplerName)
              registeredMBeans += localQueueSamplerName
            } catch {
              case _: Throwable =>
            }

            i += 1
          }

          threadPool.sleepers.zipWithIndex.foreach {
            case (timerHeap, i) =>
              try {
                val timerHeapSamplerName = new ObjectName(
                  s"cats.effect.unsafe.metrics:type=TimerHeap-$threadPoolId-$i"
                )
                val timerHeapSampler = new TimerHeapSampler(timerHeap)
                mBeanServer.registerMBean(timerHeapSampler, timerHeapSamplerName)
                registeredMBeans += timerHeapSamplerName
              } catch {
                case _: Throwable =>
              }
          }

          threadPool.pollers.zipWithIndex.foreach {
            case (poller, i) =>
              try {
                val pollerSamplerName = new ObjectName(
                  s"cats.effect.unsafe.metrics:type=Poller-$threadPoolId-$i"
                )
                val pollerSampler = new PollerSampler(threadPool.system.metrics(poller))
                mBeanServer.registerMBean(pollerSampler, pollerSamplerName)
                registeredMBeans += pollerSamplerName
              } catch {
                case _: Throwable =>
              }
          }

          threadPool.metrices.zipWithIndex.foreach {
            case (thread, i) =>
              try {
                val workerThreadSamplerName = new ObjectName(
                  s"cats.effect.unsafe.metrics:type=WorkerThread-$threadPoolId-$i"
                )
                val workerThreadSampler = new WorkerThreadSampler(thread)
                mBeanServer.registerMBean(workerThreadSampler, workerThreadSamplerName)
                registeredMBeans += workerThreadSamplerName
              } catch {
                case _: Throwable =>
              }
          }

          () => {
            if (mBeanServer ne null) {
              registeredMBeans.foreach { mbean =>
                try mBeanServer.unregisterMBean(mbean)
                catch {
                  case _: Throwable =>
                  // Do not report issues with mbeans deregistration.
                }
              }
            }
          }
        } else () => ()
      } else () => ()

    (
      threadPool,
      pollingSystem.makeApi(threadPool),
      { () =>
        unregisterMBeans()
        threadPool.shutdown()
      })
  }

  @deprecated(
    message = "Replaced by the simpler and safer `createWorkStealingComputePool`",
    since = "3.4.0"
  )
  def createDefaultComputeThreadPool(
      self: => IORuntime,
      threads: Int = Math.max(2, Runtime.getRuntime().availableProcessors()),
      threadPrefix: String = "io-compute",
      blockerThreadPrefix: String = DefaultBlockerPrefix)
      : (ExecutionContextExecutor with Scheduler, () => Unit) =
    createWorkStealingComputeThreadPool(
      threads,
      threadPrefix,
      blockerThreadPrefix,
      60.seconds,
      _.printStackTrace(),
      false
    )

  @deprecated("bincompat shim for previous default method overload", "3.3.13")
  def createDefaultComputeThreadPool(
      self: () => IORuntime,
      threads: Int,
      threadPrefix: String): (ExecutionContextExecutor with Scheduler, () => Unit) =
    createDefaultComputeThreadPool(self(), threads, threadPrefix)

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

  def createDefaultScheduler(threadPrefix: String = "io-scheduler"): (Scheduler, () => Unit) =
    Scheduler.createDefaultScheduler(threadPrefix)

  def createDefaultPollingSystem(): PollingSystem = SelectorSystem()

  @volatile private[this] var _global: IORuntime = null

  // we don't need to synchronize this with IOApp, because we control the main thread
  // so instead we just yolo it since the lazy val already synchronizes its own initialization
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
    if (isStackTracing) {
      val mBeanServer =
        try ManagementFactory.getPlatformMBeanServer()
        catch {
          case _: Throwable => null
        }

      if (mBeanServer ne null) {
        try {
          val mbeanId = LiveFiberSnapshotTrigger.IdCounter.getAndIncrement()
          val liveFiberSnapshotTriggerName = new ObjectName(
            s"cats.effect.unsafe.metrics:type=LiveFiberSnapshotTrigger-$mbeanId")
          val liveFiberSnapshotTrigger = new LiveFiberSnapshotTrigger(fiberMonitor)
          mBeanServer.registerMBean(liveFiberSnapshotTrigger, liveFiberSnapshotTriggerName)

          () => {
            try mBeanServer.unregisterMBean(liveFiberSnapshotTriggerName)
            catch {
              case _: Throwable =>
              // Do not report issues with mbeans deregistration.
            }
          }
        } catch {
          case _: Throwable => () => ()
        }
      } else () => ()
    } else () => ()
  }
}
