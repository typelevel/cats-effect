/*
 * Copyright 2020-2022 Typelevel
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
import scala.concurrent.ExecutionContext

import java.lang.management.ManagementFactory
import java.util.concurrent.{Executors, ScheduledThreadPoolExecutor}
import java.util.concurrent.atomic.AtomicInteger

import javax.management.ObjectName

private[unsafe] abstract class IORuntimeCompanionPlatform { this: IORuntime.type =>

  // The default compute thread pool on the JVM is now a work stealing thread pool.
  def createDefaultComputeThreadPool(
      self: => IORuntime,
      threads: Int = Math.max(2, Runtime.getRuntime().availableProcessors()),
      threadPrefix: String = "io-compute"): (WorkStealingThreadPool, () => Unit) = {
    val threadPool =
      new WorkStealingThreadPool(threads, threadPrefix, self)

    val unregisterMBeans =
      if (isStackTracing) {
        val mBeanServer =
          try ManagementFactory.getPlatformMBeanServer()
          catch {
            case _: Throwable => null
          }

        if (mBeanServer ne null) {
          val registeredMBeans = mutable.Set.empty[ObjectName]

          val hash = System.identityHashCode(threadPool).toHexString

          try {
            val computePoolSamplerName = new ObjectName(
              s"cats.effect.unsafe.metrics:type=ComputePoolSampler-$hash")
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
                s"cats.effect.unsafe.metrics:type=LocalQueueSampler-$hash-$i")
              val localQueueSampler = new LocalQueueSampler(localQueue)
              mBeanServer.registerMBean(localQueueSampler, localQueueSamplerName)
              registeredMBeans += localQueueSamplerName
            } catch {
              case _: Throwable =>
            }

            i += 1
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
      { () =>
        unregisterMBeans()
        threadPool.shutdown()
      })
  }

  def createDefaultBlockingExecutionContext(
      threadPrefix: String = "io-blocking"): (ExecutionContext, () => Unit) = {
    val threadCount = new AtomicInteger(0)
    val executor = Executors.newCachedThreadPool { (r: Runnable) =>
      val t = new Thread(r)
      t.setName(s"${threadPrefix}-${threadCount.getAndIncrement()}")
      t.setDaemon(true)
      t
    }
    (ExecutionContext.fromExecutor(executor), { () => executor.shutdown() })
  }

  def createDefaultScheduler(threadPrefix: String = "io-scheduler"): (Scheduler, () => Unit) = {
    val scheduler = new ScheduledThreadPoolExecutor(
      1,
      { r =>
        val t = new Thread(r)
        t.setName(threadPrefix)
        t.setDaemon(true)
        t.setPriority(Thread.MAX_PRIORITY)
        t
      })
    scheduler.setRemoveOnCancelPolicy(true)
    (Scheduler.fromScheduledExecutor(scheduler), { () => scheduler.shutdown() })
  }

  private[this] var _global: IORuntime = null

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

  lazy val global: IORuntime = {
    if (_global == null) {
      installGlobal {
        val (compute, _) = createDefaultComputeThreadPool(global)
        val (blocking, _) = createDefaultBlockingExecutionContext()
        val (scheduler, _) = createDefaultScheduler()
        IORuntime(compute, blocking, scheduler, () => (), IORuntimeConfig())
      }
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
        val hash = System.identityHashCode(fiberMonitor).toHexString

        try {
          val liveFiberSnapshotTriggerName = new ObjectName(
            s"cats.effect.unsafe.metrics:type=LiveFiberSnapshotTrigger-$hash")
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
