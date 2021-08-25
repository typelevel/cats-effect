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

import java.lang.ref.WeakReference

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

  // this is a best-effort structure and may lose data depending on thread publication
  private[this] var buffer: Array[WeakReference[IOFiber[_]]] =
    new Array[WeakReference[IOFiber[_]]](16)
  private[this] var index: Int = 0

  // used to lazily defragment the buffer (in particular, when (fragments / index) > 0.5)
  private[this] var fragments: Int = 0

  private[effect] def suspended(): List[IOFiber[_]] = {
    var back: List[IOFiber[_]] = Nil
    val buf = buffer
    val max = index

    var i = 0
    while (i < max) {
      val ref = buf(i)
      if (ref != null) {
        val fiber = ref.get()
        if (fiber != null) {
          back ::= fiber
        }
      }
      i += 1
    }

    back
  }

  private[effect] def monitor(self: IOFiber[_]): Int = {
    checkAndGrow()
    val idx = index
    buffer(idx) = new WeakReference(self)
    index += 1
    idx
  }

  private[effect] def unmonitor(idx: Int): Unit = {
    buffer(idx) = null
    fragments += 1
  }

  private[this] def checkAndGrow(): Unit = {
    if (index >= buffer.length) {
      if (fragments > index / 2) {
        val len = buffer.length
        val buffer2 = new Array[WeakReference[IOFiber[_]]](len)

        var i = 0
        var index2 = 0
        while (i < len) {
          val ref = buffer(i)
          if (ref != null && ref.get() != null) {
            val fiber = ref.get()
            if (fiber != null) {
              buffer2(index2) = ref
              fiber.updateMonitorIndex(index2)
              index2 += 1
            }
          }
          i += 1
        }

        buffer = buffer2
        index = index2
        fragments = 0
        checkAndGrow()
      } else {
        val len = buffer.length
        val buffer2 = new Array[WeakReference[IOFiber[_]]](len * 2)
        System.arraycopy(buffer, 0, buffer2, 0, len)
        buffer = buffer2
      }
    }
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
