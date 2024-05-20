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

  // TODO unify this with the defaults in IORuntime.global and IOApp
  protected def platformSpecificBuild: IORuntime = {
    val (compute, poller, computeShutdown) =
      customCompute
        .map {
          case (c, s) =>
            (c, Nil, s)
        }
        .getOrElse {
          val (c, p, s) =
            IORuntime.createWorkStealingComputeThreadPool(
              pollingSystem =
                customPollingSystem.getOrElse(IORuntime.createDefaultPollingSystem()),
              reportFailure = failureReporter
            )
          (c, List(p), s)
        }
    val xformedCompute = computeTransform(compute)

    val (scheduler, schedulerShutdown) = xformedCompute match {
      case sched: Scheduler => customScheduler.getOrElse((sched, () => ()))
      case _ => customScheduler.getOrElse(IORuntime.createDefaultScheduler())
    }

    val (blocking, blockingShutdown) =
      customBlocking.getOrElse(IORuntime.createDefaultBlockingExecutionContext())
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
      poller ::: extraPollers.map(_._1),
      shutdown,
      runtimeConfig
    )
  }

}
