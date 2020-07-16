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

private[effect] final class BooleanArrayStack(
    private[this] var buffer: Array[Boolean],
    private[this] var index: Int) {

  def this(initBound: Int) =
    this(new Array[Boolean](initBound), 0)

  def push(a: Boolean): Unit = {
    checkAndGrow()
    buffer(index) = a
    index += 1
  }

  // TODO remove bounds check
  def pop(): Boolean = {
    index -= 1
    val back = buffer(index)
    back
  }

  def peek(): Boolean = buffer(index - 1)

  def isEmpty(): Boolean = index <= 0

  // to allow for external iteration
  def unsafeBuffer(): Array[Boolean] = buffer.asInstanceOf[Array[Boolean]]
  def unsafeIndex(): Int = index

  def invalidate(): Unit = {
    index = 0
    buffer = null
  }

  def copy(): BooleanArrayStack = {
    val buffer2 = if (index == 0) {
      new Array[Boolean](buffer.length)
    } else {
      val buffer2 = new Array[Boolean](buffer.length)
      System.arraycopy(buffer, 0, buffer2, 0, buffer.length)
      buffer2
    }

    new BooleanArrayStack(buffer2, index)
  }

  private[this] def checkAndGrow(): Unit =
    if (index >= buffer.length) {
      val len = buffer.length
      val buffer2 = new Array[Boolean](len * 2)
      System.arraycopy(buffer, 0, buffer2, 0, len)
      buffer = buffer2
    }
}
