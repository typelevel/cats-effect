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

import org.scalajs.macrotaskexecutor.MacrotaskExecutor

import scala.concurrent.ExecutionContext

private[unsafe] abstract class IORuntimeCompanionPlatform { this: IORuntime.type =>

  def defaultComputeExecutionContext: ExecutionContext = MacrotaskExecutor

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

  lazy val global: IORuntime = {
    if (_global == null) {
      installGlobal {
        IORuntime(
          defaultComputeExecutionContext,
          defaultComputeExecutionContext,
          defaultScheduler,
          () => (),
          IORuntimeConfig())
      }
    }

    _global
  }
}
