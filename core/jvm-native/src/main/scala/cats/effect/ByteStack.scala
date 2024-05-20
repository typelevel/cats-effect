/*
 * Copyright 2020-2024 Typelevel
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

import Platform.static

private[effect] final class ByteStack

private object ByteStack {

  type T = Array[Int]

  @static final def toDebugString(
      stack: Array[Int],
      translate: Byte => String = _.toString): String = {
    val count = size(stack)
    ((count - 1) to 0 by -1)
      .foldLeft(
        new StringBuilder()
          .append("Stack:")
          .append(" capacity = ")
          .append((stack.length - 1) * 8)
          .append(',')
          .append(" count = ")
          .append(count)
          .append(',')
          .append(" content (top-first) = [ ")
      ) { (b, i) => b.append(translate(ByteStack.read(stack, i))).append(' ') }
      .append(']')
      .toString
  }

  @static final def create(initialMaxOps: Int): Array[Int] =
    new Array[Int](1 + 1 + ((initialMaxOps - 1) >> 3)) // count-slot + 1 for each set of 8 ops

  @static final def growIfNeeded(stack: Array[Int], count: Int): Array[Int] = {
    if ((1 + ((count + 1) >> 3)) < stack.length) {
      stack
    } else {
      val bigger = new Array[Int](stack.length << 1)
      System.arraycopy(stack, 0, bigger, 0, stack.length) // Count in stack(0) copied "for free"
      bigger
    }
  }

  @static final def push(stack: Array[Int], op: Byte): Array[Int] = {
    val c = stack(0) // current count of elements
    val use = growIfNeeded(stack, c) // alias so we add to the right place
    val s = (c >> 3) + 1 // current slot in `use`
    val shift = (c & 7) << 2 // BEGIN MAGIC
    use(s) = (use(s) & ~(0xffffffff << shift)) | (op << shift) // END MAGIC
    use(0) += 1 // write the new count
    use
  }

  @static final def size(stack: Array[Int]): Int =
    stack(0)

  @static final def isEmpty(stack: Array[Int]): Boolean =
    stack(0) < 1

  @static final def read(stack: Array[Int], pos: Int): Byte = {
    if (pos < 0 || pos >= stack(0)) throw new ArrayIndexOutOfBoundsException()
    ((stack((pos >> 3) + 1) >>> ((pos & 7) << 2)) & 0x0000000f).toByte
  }

  @static final def peek(stack: Array[Int]): Byte = {
    val c = stack(0) - 1
    if (c < 0) throw new ArrayIndexOutOfBoundsException()
    ((stack((c >> 3) + 1) >>> ((c & 7) << 2)) & 0x0000000f).toByte
  }

  @static final def pop(stack: Array[Int]): Byte = {
    val op = peek(stack)
    stack(0) -= 1
    op
  }
}
