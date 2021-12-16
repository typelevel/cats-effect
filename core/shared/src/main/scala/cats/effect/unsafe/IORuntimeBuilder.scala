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

final class IORuntimeBuilder protected (
    var computeWrapper: ExecutionContext => ExecutionContext = identity,
    var blockingWrapper: ExecutionContext => ExecutionContext = identity,
    var customConfig: Option[IORuntimeConfig] = None,
    var customScheduler: Option[Scheduler] = None,
    var customShutdown: Option[() => Unit] = None
) extends IORuntimeBuilderPlatform {
  def withComputeWrapper(wrapper: ExecutionContext => ExecutionContext) =
    computeWrapper = wrapper.andThen(computeWrapper)

  def withBlockingWrapper(wrapper: ExecutionContext => ExecutionContext) =
    blockingWrapper = wrapper.andThen(blockingWrapper)

  def withConfig(config: IORuntimeConfig) =
    customConfig = Some(config)

  def withScheduler(scheduler: Scheduler) =
    customScheduler = Some(scheduler)

  def withShutdown(shutdown: () => Unit) =
    customShutdown = Some(shutdown)

  def build: IORuntime =
    build(computeWrapper, blockingWrapper, customConfig, customScheduler, customShutdown)
}

object IORuntimeBuilder {
  def apply(): IORuntimeBuilder =
    new IORuntimeBuilder()
}
