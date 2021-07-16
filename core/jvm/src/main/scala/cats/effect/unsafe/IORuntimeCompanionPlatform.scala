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

package cats.effect.unsafe

import cats.effect.unsafe.metrics.ComputePoolSampler

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
      threadPrefix: String = "io-compute"): (WorkStealingThreadPool, () => Unit) =
    createDefaultComputeThreadPoolWithMBeansConfig(
      self,
      threads,
      threadPrefix,
      MetricsConstants.metricsEnabled)

  private[unsafe] def createDefaultComputeThreadPoolWithMBeansConfig(
      self: => IORuntime,
      threads: Int = Math.max(2, Runtime.getRuntime().availableProcessors()),
      threadPrefix: String = "io-compute",
      registerMBeans: Boolean): (WorkStealingThreadPool, () => Unit) = {
    val threadPool =
      new WorkStealingThreadPool(threads, threadPrefix, self)

    val unregisterMBeans =
      if (registerMBeans) {
        val mBeanServer =
          try ManagementFactory.getPlatformMBeanServer()
          catch {
            case t: Throwable =>
              t.printStackTrace()
              null
          }

        val registeredObjects = mutable.Set.empty[ObjectName]

        if (mBeanServer ne null) {
          val computePoolSamplerName =
            new ObjectName("cats.effect.metrics:type=ComputePoolSampler")
          val computePoolSampler = new ComputePoolSampler(threadPool)

          try {
            mBeanServer.registerMBean(computePoolSampler, computePoolSamplerName)
            registeredObjects += computePoolSamplerName
          } catch {
            case t: Throwable =>
              t.printStackTrace()
          }
        }

        () => {
          if (mBeanServer ne null) {
            registeredObjects.foreach(mBeanServer.unregisterMBean)
          }
        }
      } else () => ()

    (
      threadPool,
      { () =>
        unregisterMBeans()
        threadPool.shutdown()
      }
    )
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
}
