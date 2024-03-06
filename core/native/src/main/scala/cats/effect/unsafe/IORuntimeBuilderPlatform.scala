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

private[unsafe] abstract class IORuntimeBuilderPlatform { self: IORuntimeBuilder =>

  protected var customPollingSystem: Option[PollingSystem] = None

  /**
   * Override the default [[PollingSystem]]
   */
  def setPollingSystem(system: PollingSystem): IORuntimeBuilder = {
    if (customPollingSystem.isDefined) {
      throw new RuntimeException("Polling system can only be set once")
    }
    customPollingSystem = Some(system)
    this
  }

  protected def platformSpecificBuild: IORuntime = {
    val defaultShutdown: () => Unit = () => ()
    lazy val (loop, poller, loopDown) = IORuntime.createEventLoop(
      customPollingSystem.getOrElse(IORuntime.createDefaultPollingSystem())
    )
    val (compute, pollers, computeShutdown) =
      customCompute
        .map { case (c, s) => (c, Nil, s) }
        .getOrElse(
          (
            loop,
            List(poller),
            loopDown
          ))
    val (blocking, blockingShutdown) = customBlocking.getOrElse((compute, defaultShutdown))
    val (scheduler, schedulerShutdown) =
      customScheduler.getOrElse((loop, defaultShutdown))
    val shutdown = () => {
      computeShutdown()
      blockingShutdown()
      schedulerShutdown()
      extraPollers.foreach(_._2())
      extraShutdownHooks.reverse.foreach(_())
    }
    val runtimeConfig = customConfig.getOrElse(IORuntimeConfig())

    IORuntime.apply(
      computeTransform(compute),
      blockingTransform(blocking),
      scheduler,
      pollers ::: extraPollers.map(_._1),
      shutdown,
      runtimeConfig
    )
  }

}
