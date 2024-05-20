/*
 * Copyright 2020-2024 Typelevel
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

private[unsafe] abstract class IORuntimeCompanionPlatform { this: IORuntime.type =>

  def defaultComputeExecutionContext: ExecutionContext =
    createBatchingMacrotaskExecutor()

  def createBatchingMacrotaskExecutor(
      batchSize: Int = 64,
      reportFailure: Throwable => Unit = _.printStackTrace()
  ): ExecutionContext =
    new BatchingMacrotaskExecutor(batchSize, reportFailure)

  def defaultScheduler: Scheduler = Scheduler.createDefaultScheduler()._1

  private[this] var _global: IORuntime = null

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
        IORuntime(
          defaultComputeExecutionContext,
          defaultComputeExecutionContext,
          defaultScheduler,
          () => resetGlobal(),
          IORuntimeConfig())
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
