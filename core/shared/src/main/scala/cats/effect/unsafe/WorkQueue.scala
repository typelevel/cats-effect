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

import java.util.concurrent.atomic.AtomicInteger

/**
 * Circular buffer implementation of the pseudocode presented in the paper "Dynamic Circular Work-Stealing Deque" by
 * Chase and Lev, SPAA 2005 (https://www.dre.vanderbilt.edu/~schmidt/PDF/work-stealing-dequeue.pdf).
 */
private[effect] final class WorkQueue {
  @volatile private[this] var bottom: Int = 0
  private[this] val top: AtomicInteger = new AtomicInteger()
  @volatile private[this] var buffer: CircularBuffer = new CircularBuffer(
    WorkQueue.LogInitialSize)

  private[this] def casTop(oldVal: Int, newVal: Int): Boolean =
    top.compareAndSet(oldVal, newVal)

  def push(fiber: IOFiber[_]): Unit = {
    val b = bottom
    val t = top.get()
    var cb = buffer
    val size = b - t
    if (size >= cb.size - 1) {
      cb = cb.grow(b, t)
      buffer = cb
    }
    cb.put(b, fiber)
    bottom += 1
  }

  def steal(): IOFiber[_] = {
    val t = top.get()
    val b = bottom
    val cb = buffer
    val size = b - t
    if (size <= 0) null
    else {
      val fiber = cb.get(t)
      if (!casTop(t, t + 1)) null
      else fiber
    }
  }
}

private[effect] object WorkQueue {
  final val LogInitialSize = 13
}
