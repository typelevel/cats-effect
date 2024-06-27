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

package cats.effect
package unsafe

import cats.effect.Platform.static

import scala.concurrent.ExecutionContext

import java.util.concurrent.atomic.AtomicBoolean

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
final class IORuntime private[unsafe] (
    val compute: ExecutionContext,
    private[effect] val blocking: ExecutionContext,
    val scheduler: Scheduler,
    private[effect] val pollers: List[Any],
    private[effect] val fiberMonitor: FiberMonitor,
    val shutdown: () => Unit,
    val config: IORuntimeConfig
) {

  private[effect] val fiberErrorCbs: StripedHashtable = new StripedHashtable()

  /*
   * Forwarder methods for `IOFiber`.
   */
  private[effect] val cancelationCheckThreshold: Int = config.cancelationCheckThreshold
  private[effect] val autoYieldThreshold: Int = config.autoYieldThreshold
  private[effect] val enhancedExceptions: Boolean = config.enhancedExceptions
  private[effect] val traceBufferLogSize: Int = config.traceBufferLogSize

  override def toString: String = s"IORuntime($compute, $scheduler, $config)"
}

object IORuntime extends IORuntimeCompanionPlatform {

  def apply(
      compute: ExecutionContext,
      blocking: ExecutionContext,
      scheduler: Scheduler,
      pollers: List[Any],
      shutdown: () => Unit,
      config: IORuntimeConfig): IORuntime = {
    val fiberMonitor = FiberMonitor(compute)
    val unregister = registerFiberMonitorMBean(fiberMonitor)
    def unregisterAndShutdown: () => Unit = () => {
      unregister()
      shutdown()
      allRuntimes.remove(runtime, runtime.hashCode())
    }

    lazy val runtime =
      new IORuntime(
        compute,
        blocking,
        scheduler,
        pollers,
        fiberMonitor,
        unregisterAndShutdown,
        config)
    allRuntimes.put(runtime, runtime.hashCode())
    runtime
  }

  def apply(
      compute: ExecutionContext,
      blocking: ExecutionContext,
      scheduler: Scheduler,
      shutdown: () => Unit,
      config: IORuntimeConfig): IORuntime =
    apply(compute, blocking, scheduler, Nil, shutdown, config)

  @deprecated("Preserved for bincompat", "3.6.0")
  private[unsafe] def apply(
      compute: ExecutionContext,
      blocking: ExecutionContext,
      scheduler: Scheduler,
      fiberMonitor: FiberMonitor,
      shutdown: () => Unit,
      config: IORuntimeConfig): IORuntime =
    new IORuntime(compute, blocking, scheduler, Nil, fiberMonitor, shutdown, config)

  def builder(): IORuntimeBuilder =
    IORuntimeBuilder()

  private[effect] def testRuntime(ec: ExecutionContext, scheduler: Scheduler): IORuntime =
    new IORuntime(ec, ec, scheduler, Nil, new NoOpFiberMonitor(), () => (), IORuntimeConfig())

  @static private[effect] final val allRuntimes: ThreadSafeHashtable[IORuntime] =
    new ThreadSafeHashtable(4)

  @static private[effect] final val globalFatalFailureHandled: AtomicBoolean =
    new AtomicBoolean(false)
}
