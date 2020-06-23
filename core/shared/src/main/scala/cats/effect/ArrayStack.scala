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

// not currently safe to observe from multiple threads
private[effect] final class ArrayStack[A <: AnyRef](initBound: Int) {

  private[this] var buffer: Array[AnyRef] = new Array[AnyRef](initBound)
  private[this] var index: Int = 0

  def push(a: A): Unit = {
    checkAndGrow()
    buffer(index) = a
    index += 1
  }

  // TODO remove bounds check
  def pop(): A = {
    index -= 1
    val back = buffer(index).asInstanceOf[A]
    buffer(index) = null    // avoid memory leaks
    back
  }

  def peek(): A = buffer(index).asInstanceOf[A]

  def isEmpty(): Boolean = index <= 0

  private[this] def checkAndGrow(): Unit = {
    if (index >= buffer.length) {
      val len = buffer.length
      val buffer2 = new Array[AnyRef](len * 2)
      System.arraycopy(buffer, 0, buffer2, 0, len)
      buffer = buffer2
    }
  }
}
