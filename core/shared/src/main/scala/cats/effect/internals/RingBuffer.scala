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
final private[internals] class RingBuffer[A <: AnyRef](logSize: Int) {

  // These two probably don't need to be allocated every single time, maybe in Java?
  private[this] val length = 1 << logSize
  private[this] val mask = length - 1

  private[this] val array: Array[AnyRef] = new Array(length)
  private[this] var index: Int = 0

  def push(a: A): A = {
    val wi = index & mask
    val old = array(wi).asInstanceOf[A]
    array(wi) = a
    index += 1
    old
  }

  def isEmpty: Boolean =
    index == 0

  def capacity: Int =
    length

  // returns a list in reverse order of insertion
  def toList: List[A] = {
    val start = index - 1
    val end = Math.max(index - length, 0)
    (start to end by -1).toList
      .map(i => array(i & mask).asInstanceOf[A])
  }

}
