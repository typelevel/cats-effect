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

import scala.scalajs.js

private object ByteStack {

  type T = js.Array[Int]

  @inline final def create(initialMaxOps: Int): js.Array[Int] = {
    val _ = initialMaxOps
    js.Array(0)
  }

  @inline final def growIfNeeded(stack: js.Array[Int], count: Int): js.Array[Int] = {
    if ((1 + ((count + 1) >> 3)) < stack.length) {
      stack
    } else {
      stack.push(0)
      stack
    }
  }

  @inline final def push(stack: js.Array[Int], op: Byte): js.Array[Int] = {
    val c = stack(0) // current count of elements
    val use = growIfNeeded(stack, c) // alias so we add to the right place
    val s = (c >> 3) + 1 // current slot in `use`
    val shift = (c & 7) << 2 // BEGIN MAGIC
    use(s) = (use(s) & ~(0xffffffff << shift)) | (op << shift) // END MAGIC
    use(0) += 1 // write the new count
    use
  }

  @inline final def size(stack: js.Array[Int]): Int =
    stack(0)

  @inline final def isEmpty(stack: js.Array[Int]): Boolean =
    stack(0) < 1

  @inline final def read(stack: js.Array[Int], pos: Int): Byte = {
    if (pos < 0 || pos >= stack(0)) throw new ArrayIndexOutOfBoundsException()
    ((stack((pos >> 3) + 1) >>> ((pos & 7) << 2)) & 0x0000000f).toByte
  }

  @inline final def peek(stack: js.Array[Int]): Byte = {
    val c = stack(0) - 1
    if (c < 0) throw new ArrayIndexOutOfBoundsException()
    ((stack((c >> 3) + 1) >>> ((c & 7) << 2)) & 0x0000000f).toByte
  }

  @inline final def pop(stack: js.Array[Int]): Byte = {
    val op = peek(stack)
    stack(0) -= 1
    op
  }
}
