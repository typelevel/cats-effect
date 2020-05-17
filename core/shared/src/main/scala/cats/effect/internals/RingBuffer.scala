/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals

/**
 * Provides a fast, mutable ring buffer.
 *
 * INTERNAL API.
 */
final private[internals] class RingBuffer[A <: AnyRef](size: Int) {

  import RingBuffer._

  private[this] val capacity = nextPowerOfTwo(size)
  private[this] val mask = capacity - 1

  // TODO: this can be an expensive allocation
  // either construct it lazily or expand it on-demand
  private[this] val array: Array[AnyRef] = new Array(capacity)
  private[this] var writeIndex: Int = 0
  private[this] var readIndex: Int = 0

  def push(a: A): A = {
    val wi = writeIndex & mask
    if (writeIndex == readIndex + capacity) {
      val old = array(wi)
      array(wi) = a
      // TODO: overflow at int.maxvalue?
      writeIndex = writeIndex + 1
      readIndex = readIndex + 1
      old.asInstanceOf[A]
    } else {
      array(wi) = a
      writeIndex = writeIndex + 1
      null.asInstanceOf[A]
    }
  }

  // TODO: expose this as an iterator instead?
  def toList: List[A] =
    (readIndex until writeIndex).toList
      .map(i => array(i & mask).asInstanceOf[A])

}

object RingBuffer {

  // TODO: bounds check at int.maxvalue ?
  private def nextPowerOfTwo(i: Int): Int = {
    var n = 1
    while (n < i) {
      n = n * 2
    }
    n
  }

}
