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

package cats.effect
package unsafe

import scala.concurrent.ExecutionContext

case class IORuntimeBuilder(
  computeWrapper: ExecutionContext => ExecutionContext = identity,
  blockingWrapper: ExecutionContext => ExecutionContext = identity,
  customConfig: Option[IORuntimeConfig] = None,
  customScheduler: Option[Scheduler] = None,
  customShutdown: Option[() => Unit] = None,
) {
  def withComputeWrapper(wrapper: ExecutionContext => ExecutionContext) =
    this.copy(computeWrapper = wrapper.andThen(computeWrapper))

  def withBlockingWrapper(wrapper: ExecutionContext => ExecutionContext) =
    this.copy(blockingWrapper = wrapper.andThen(blockingWrapper))

  def withConfig(config: IORuntimeConfig) = 
    this.copy(customConfig = Some(config))

  def withScheduler(scheduler: Scheduler) = 
    this.copy(customScheduler = Some(scheduler))

  def withShutdown(shutdown: () => Unit) = 
    this.copy(customShutdown = Some(shutdown))

  def build = {
    var runtime: IORuntime = null
    val compute = IORuntime.createDefaultComputeThreadPool(runtime)._1
    val blocking = IORuntime.createDefaultBlockingExecutionContext()._1
    val scheduler = customScheduler.getOrElse(IORuntime.createDefaultScheduler()._1)
    val shutdown = customShutdown.getOrElse(() => ())
    val runtimeConfig = customConfig.getOrElse(IORuntimeConfig())

    runtime = IORuntime.apply(
      computeWrapper(compute),
      blockingWrapper(blocking),
      scheduler,
      shutdown,
      runtimeConfig
    )
    runtime
  }
}


