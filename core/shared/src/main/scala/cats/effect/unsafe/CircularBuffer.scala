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

/**
 * Circular buffer implementation of the pseudocode presented in the paper "Dynamic Circular Work-Stealing Deque" by
 * Chase and Lev, SPAA 2005 (https://www.dre.vanderbilt.edu/~schmidt/PDF/work-stealing-dequeue.pdf).
 */
private final class CircularBuffer(private[this] val logSize: Int) {
  private[unsafe] val buffer: Array[IOFiber[_]] = new Array[IOFiber[_]](1 << logSize)

  val size: Int = 1 << logSize

  def get(index: Int): IOFiber[_] =
    buffer(index % size)

  def put(index: Int, fiber: IOFiber[_]): Unit =
    buffer(index % size) = fiber

  def grow(bottom: Int, top: Int): CircularBuffer = {
    val buf = new CircularBuffer(logSize + 1)
    val len = bottom - top
    System.arraycopy(buffer, top, buf.buffer, top, len)
    buf
  }
}
