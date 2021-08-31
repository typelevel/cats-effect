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

import scala.annotation.tailrec
import scala.collection.mutable

import java.lang.ref.WeakReference

private[unsafe] abstract class IORuntimePlatform { this: IORuntime =>

  // this is a best-effort structure and may lose data depending on thread publication
  private[this] var buffer: Array[WeakReference[IOFiber[_]]] =
    new Array[WeakReference[IOFiber[_]]](16)
  private[this] var index: Int = 0

  // used to lazily defragment the buffer (in particular, when (fragments / index) > 0.5)
  private[this] var fragments: Int = 0

  val shutdown: () => Unit = { () =>
    this._shutdown()
    buffer = null
  }

  private[effect] def suspended(): Set[IOFiber[_]] = {
    val back = mutable.Set[IOFiber[_]]()
    val buf = buffer
    val max = index

    var i = 0
    while (i < max) {
      val ref = buf(i)
      if (ref ne null) {
        val fiber = ref.get()
        if (fiber ne null) {
          back += fiber
        }
      }
      i += 1
    }

    back.toSet
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

  @tailrec
  private[this] def checkAndGrow(): Unit = {
    if (index >= buffer.length) {
      if (fragments > index / 2) {
        val len = buffer.length
        val buffer2 = new Array[WeakReference[IOFiber[_]]](len)

        var i = 0
        var index2 = 0
        while (i < len) {
          val ref = buffer(i)
          if ((ref ne null) && (ref.get() ne null)) {
            val fiber = ref.get()
            if (fiber ne null) {
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
}
