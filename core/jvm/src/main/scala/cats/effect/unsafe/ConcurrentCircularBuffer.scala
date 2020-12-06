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

private final class ConcurrentCircularBuffer(initialCapacity: Int)
    extends ConcurrentCircularBuffer.TailPadding {

  private[this] val headOffset: Long = {
    try {
      val field = classOf[ConcurrentCircularBuffer.Head].getDeclaredField("head")
      Unsafe.objectFieldOffset(field)
    } catch {
      case t: Throwable =>
        throw new ExceptionInInitializerError(t)
    }
  }

  private[this] val tailOffset: Long = {
    try {
      val field = classOf[ConcurrentCircularBuffer.Tail].getDeclaredField("tail")
      Unsafe.objectFieldOffset(field)
    } catch {
      case t: Throwable =>
        throw new ExceptionInInitializerError(t)
    }
  }

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
    val tl = Unsafe.getAndAddInt(this, tailOffset, 1)
    val idx = index(tl)
    buffer(idx) = wt
  }

  def dequeue(): WorkerThread = {
    var idx = 0
    var hd = 0
    var tl = 0
    var nx = 0

    while ({
      hd = Unsafe.getInt(this, headOffset)
      tl = Unsafe.getInt(this, tailOffset)

      if (hd == tl)
        return null

      idx = hd
      nx = hd + 1
      !Unsafe.compareAndSwapInt(this, headOffset, hd, nx)
    }) ()

    idx = index(idx)
    val t = buffer(idx)
    buffer(idx) = null
    t
  }
}

private object ConcurrentCircularBuffer {
  abstract class InitPadding {
    protected var pinit00: Long = _
    protected var pinit01: Long = _
    protected var pinit02: Long = _
    protected var pinit03: Long = _
    protected var pinit04: Long = _
    protected var pinit05: Long = _
    protected var pinit06: Long = _
    protected var pinit07: Long = _
    protected var pinit08: Long = _
    protected var pinit09: Long = _
    protected var pinit10: Long = _
    protected var pinit11: Long = _
    protected var pinit12: Long = _
    protected var pinit13: Long = _
    protected var pinit14: Long = _
    protected var pinit15: Long = _
  }

  abstract class Head extends InitPadding {
    @volatile protected var head: Int = 0
  }

  abstract class HeadPadding extends Head {
    protected var phead00: Long = _
    protected var phead01: Long = _
    protected var phead02: Long = _
    protected var phead03: Long = _
    protected var phead04: Long = _
    protected var phead05: Long = _
    protected var phead06: Long = _
    protected var phead07: Long = _
    protected var phead08: Long = _
    protected var phead09: Long = _
    protected var phead10: Long = _
    protected var phead11: Long = _
    protected var phead12: Long = _
    protected var phead13: Long = _
    protected var phead14: Long = _
    protected var phead15: Long = _
  }

  abstract class Tail extends HeadPadding {
    @volatile protected var tail: Int = 0
  }

  abstract class TailPadding extends Tail {
    protected var ptail00: Long = _
    protected var ptail01: Long = _
    protected var ptail02: Long = _
    protected var ptail03: Long = _
    protected var ptail04: Long = _
    protected var ptail05: Long = _
    protected var ptail06: Long = _
    protected var ptail07: Long = _
    protected var ptail08: Long = _
    protected var ptail09: Long = _
    protected var ptail10: Long = _
    protected var ptail11: Long = _
    protected var ptail12: Long = _
    protected var ptail13: Long = _
    protected var ptail14: Long = _
    protected var ptail15: Long = _
  }
}
