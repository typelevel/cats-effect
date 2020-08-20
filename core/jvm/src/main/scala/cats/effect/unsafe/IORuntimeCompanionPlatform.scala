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

  def createDefaultComputeExecutionContext(
      self: => IORuntime,
      threadPrefix: String = "io-compute"): (WorkStealingThreadPool, () => Unit) = {
    val threadCount = Runtime.getRuntime().availableProcessors()
    val threadPool = new WorkStealingThreadPool(threadCount, threadPrefix, self)
    (threadPool, () => threadPool.shutdown())
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
    val blocking = createDefaultBlockingExecutionContext()._1
    val scheduler = createDefaultScheduler()._1
    new IORuntime(
      createDefaultComputeExecutionContext(global)._1,
      blocking,
      scheduler,
      () => ())
  }
}
