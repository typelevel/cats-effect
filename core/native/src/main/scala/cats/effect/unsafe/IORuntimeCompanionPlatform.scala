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
import scala.scalanative.meta.LinktimeInfo

private[unsafe] abstract class IORuntimeCompanionPlatform { this: IORuntime.type =>

  def defaultComputeExecutionContext: ExecutionContext = EventLoopExecutorScheduler.global

  def defaultScheduler: Scheduler = EventLoopExecutorScheduler.global

  def createEventLoop(
      system: PollingSystem
  ): (ExecutionContext with Scheduler, system.Api, () => Unit) = {
    val loop = new EventLoopExecutorScheduler[system.Poller](64, system)
    val poller = loop.poller
    (loop, system.makeApi(cb => cb(poller)), () => loop.shutdown())
  }

  def createDefaultPollingSystem(): PollingSystem =
    if (LinktimeInfo.isLinux)
      EpollSystem
    else if (LinktimeInfo.isMac)
      KqueueSystem
    else
      SleepSystem

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
        val (loop, poller, loopDown) = createEventLoop(createDefaultPollingSystem())
        IORuntime(
          loop,
          loop,
          loop,
          List(poller),
          () => {
            loopDown()
            resetGlobal()
          },
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
