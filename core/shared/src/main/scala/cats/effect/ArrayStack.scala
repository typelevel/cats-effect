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

private[effect] final class ArrayStack[A <: AnyRef](private[this] var buffer: Array[AnyRef], private[this] var index: Int) {

  def this(initBound: Int) =
    this(new Array[AnyRef](initBound), 0)

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

  def peek(): A = buffer(index - 1).asInstanceOf[A]

  def isEmpty(): Boolean = index <= 0

  // to allow for external iteration
  def unsafeBuffer(): Array[A] = buffer.asInstanceOf[Array[A]]
  def unsafeIndex(): Int = index

  def copy(): ArrayStack[A] = {
    val buffer2 = if (index == 0) {
      new Array[AnyRef](buffer.length)
    } else {
      val buffer2 = new Array[AnyRef](buffer.length)
      System.arraycopy(buffer, 0, buffer2, 0, buffer.length)
      buffer2
    }

    new ArrayStack[A](buffer2, index)
  }

  private[this] def checkAndGrow(): Unit = {
    if (index >= buffer.length) {
      val len = buffer.length
      val buffer2 = new Array[AnyRef](len * 2)
      System.arraycopy(buffer, 0, buffer2, 0, len)
      buffer = buffer2
    }
  }
}
