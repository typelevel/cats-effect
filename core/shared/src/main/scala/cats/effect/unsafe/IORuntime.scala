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
final class IORuntime private (
    val compute: ExecutionContext,
    private[effect] val blocking: ExecutionContext,
    val scheduler: Scheduler,
    val shutdown: () => Unit,
    val config: IORuntimeConfig
) {
  private[effect] val fiberErrorCbs: StripedHashtable = new StripedHashtable()

  private[effect] val suspendedFiberBag: SuspendedFiberBag = new SuspendedFiberBag()

  private[effect] def fiberDump(): Option[String] =
    Some(compute) collect {
      case compute: WorkStealingThreadPool =>
        val (yielding, active) = compute.contents()
        val suspended = suspendedFiberBag.contents()

        val strings = (yielding ++ active ++ suspended).toList map { fiber =>
          val status =
            if (yielding(fiber))
              "YIELDING"
            else if (active(fiber))
              "RUNNING"
            else
              "WAITING"

          val id = System.identityHashCode(fiber)

          s"cats.effect.IOFiber@$id $status\n" + tracing.Tracing.prettyPrint(fiber.trace())
        }

        strings.mkString("\n \n")
    }

  override def toString: String = s"IORuntime($compute, $scheduler, $config)"
}

object IORuntime extends IORuntimeCompanionPlatform {
  def apply(
      compute: ExecutionContext,
      blocking: ExecutionContext,
      scheduler: Scheduler,
      shutdown: () => Unit,
      config: IORuntimeConfig): IORuntime =
    new IORuntime(compute, blocking, scheduler, shutdown, config)
}
