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

package cats.effect
package unsafe

import scala.concurrent.ExecutionContext

@annotation.implicitNotFound("""Could not find an implicit IORuntime.

Instead of calling unsafe methods directly, consider using cats.effect.IOApp, which
runs your IO. If integrating with non-functional code or experimenting in a REPL / Worksheet,
add the following import:

import cats.effect.unsafe.implicits.global

Alternatively, you can create an explicit IORuntime value and put it in implicit scope.
This may be useful if you have a pre-existing fixed thread pool and/or scheduler which you
wish to use to execute IO programs. Please be sure to review thread pool best practices to
avoid unintentionally degrading your application performance.
""")
// Constructor visible in the effect package for use in benchmarks.
final class IORuntime private[effect] (
    val compute: ExecutionContext,
    val blocking: ExecutionContext,
    val scheduler: Scheduler,
    val shutdown: () => Unit,
    val config: IORuntimeConfig) {

  override def toString: String = s"IORuntime($compute, $scheduler, $config)"
}

final case class IORuntimeConfig private (
    val cancellationCheckThreshold: Int,
    val autoYieldThreshold: Int
)

object IORuntimeConfig {
  def apply(): IORuntimeConfig = new IORuntimeConfig(512, 1024)

  def apply(cancellationCheckThreshold: Int, autoYieldThreshold: Int): IORuntimeConfig = {
    if (autoYieldThreshold % cancellationCheckThreshold == 0)
      new IORuntimeConfig(
        cancellationCheckThreshold,
        autoYieldThreshold
      )
    else
      throw new AssertionError(
        s"Auto yield threshold $autoYieldThreshold must be a multiple of cancellation check threshold $cancellationCheckThreshold")
  }
}

object IORuntime extends IORuntimeCompanionPlatform {
  def apply(
      compute: ExecutionContext,
      blocking: ExecutionContext,
      scheduler: Scheduler,
      shutdown: () => Unit): IORuntime =
    new IORuntime(compute, blocking, scheduler, shutdown, IORuntimeConfig())

  def apply(
      compute: ExecutionContext,
      blocking: ExecutionContext,
      scheduler: Scheduler,
      shutdown: () => Unit,
      config: IORuntimeConfig): IORuntime =
    new IORuntime(compute, blocking, scheduler, shutdown, config)
}
