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
import scala.concurrent.ExecutionContext

private[unsafe] abstract class IORuntimeBuilderPlatform {

  def build(
      computeWrapper: ExecutionContext => ExecutionContext = identity,
      blockingWrapper: ExecutionContext => ExecutionContext = identity,
      customConfig: Option[IORuntimeConfig] = None,
      customScheduler: Option[Scheduler] = None,
      customShutdown: Option[() => Unit] = None
  ) = {
    var runtime: IORuntime = null
    val compute = IORuntime.defaultComputeExecutionContext
    val blocking = compute
    val scheduler = customScheduler.getOrElse(IORuntime.defaultScheduler)
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
