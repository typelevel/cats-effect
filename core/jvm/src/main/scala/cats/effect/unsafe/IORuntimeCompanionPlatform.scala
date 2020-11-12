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

package cats.effect.unsafe

import scala.concurrent.ExecutionContext

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private[unsafe] abstract class IORuntimeCompanionPlatform { this: IORuntime.type =>

  // The default compute thread pool on the JVM is now a work stealing thread pool.
  def createDefaultComputeThreadPool(
      self: => IORuntime,
      threadPrefix: String = "io-compute"): (WorkStealingThreadPool, () => Unit) = {
    val threads = math.max(2, Runtime.getRuntime().availableProcessors())
    val threadPool =
      new WorkStealingThreadPool(threads, threadPrefix, self)
    (threadPool, { () => threadPool.shutdown() })
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

  def createDefaultScheduler(threadName: String = "io-scheduler"): (Scheduler, () => Unit) = {
    val scheduler = Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r)
      t.setName(threadName)
      t.setDaemon(true)
      t.setPriority(Thread.MAX_PRIORITY)
      t
    }
    (Scheduler.fromScheduledExecutor(scheduler), { () => scheduler.shutdown() })
  }

  lazy val global: IORuntime = {
    val cancellationCheckThreshold =
      System.getProperty("cats.effect.cancellation.check.threshold", "512").toInt

    val (compute, compDown) = createDefaultComputeThreadPool(global)
    val (blocking, blockDown) = createDefaultBlockingExecutionContext()
    val (scheduler, schedDown) = createDefaultScheduler()

    new IORuntime(
      compute,
      blocking,
      scheduler,
      () => (),
      IORuntimeConfig(
        cancellationCheckThreshold,
        System
          .getProperty("cats.effect.auto.yield.threshold.multiplier", "2")
          .toInt * cancellationCheckThreshold
      ),
      internalShutdown = () => {
        compDown()
        blockDown()
        schedDown()
      }
    )
  }
}
