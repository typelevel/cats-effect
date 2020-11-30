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

package cats.effect.unsafe

import java.util.concurrent.atomic.AtomicInteger

private final class ConcurrentCircularBuffer(initialCapacity: Int) {
  private[this] val head: AtomicInteger = new AtomicInteger(0)

  private[this] val tail: AtomicInteger = new AtomicInteger(0)

  private[this] val mask: Int = {
    // Bit twiddling hacks.
    // http://graphics.stanford.edu/~seander/bithacks.html#RoundUpPowerOf2
    var value = initialCapacity - 1
    value |= value >> 1
    value |= value >> 2
    value |= value >> 4
    value |= value >> 8
    value | value >> 16
  }

  private[this] val buffer: Array[WorkerThread] = new Array(mask + 1)

  private[this] def index(src: Int): Int =
    src & mask

  def enqueue(wt: WorkerThread): Unit = {
    val tl = tail.getAndIncrement()
    val idx = index(tl)
    buffer(idx) = wt
  }

  def dequeue(): WorkerThread = {
    var idx = 0
    var hd = 0
    var tl = 0

    while ({
      hd = head.get()
      tl = tail.get()

      if (hd == tl)
        return null

      idx = hd
      !head.compareAndSet(hd, hd + 1)
    }) ()

    idx = index(idx)
    val t = buffer(idx)
    buffer(idx) = null
    t
  }
}
