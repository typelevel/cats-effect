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

package cats.effect.internals

/**
 * Provides a fast, array-based stack.
 *
 * We need this because Scala's `ArrayStack` never shrinks, only grows.
 *
 * INTERNAL API.
 */
final private[internals] class ArrayStack[A <: AnyRef] private (
  initialArray: Array[AnyRef],
  chunkSize: Int,
  initialIndex: Int
) extends Serializable { self =>

  private[this] val modulo = chunkSize - 1
  private[this] var array = initialArray
  private[this] var index = initialIndex

  def this(chunkSize: Int) = this(new Array[AnyRef](chunkSize), chunkSize, 0)
  def this() = this(8)

  /** Returns `true` if the stack is empty. */
  def isEmpty: Boolean =
    index == 0 && (array(0) eq null)

  /** Pushes an item on the stack. */
  def push(a: A): Unit = {
    if (index == modulo) {
      val newArray = new Array[AnyRef](chunkSize)
      newArray(0) = array
      array = newArray
      index = 1
    } else {
      index += 1
    }
    array(index) = a.asInstanceOf[AnyRef]
  }

  /** Pushes an entire iterator on the stack. */
  def pushAll(cursor: Iterator[A]): Unit =
    while (cursor.hasNext) push(cursor.next())

  /** Pushes an entire iterable on the stack. */
  def pushAll(seq: Iterable[A]): Unit =
    pushAll(seq.iterator)

  /** Pushes the contents of another stack on this stack. */
  def pushAll(stack: ArrayStack[A]): Unit =
    pushAll(stack.iteratorReversed)

  /** Pops an item from the stack (in LIFO order).
   *
   * Returns `null` in case the stack is empty.
   */
  def pop(): A = {
    if (index == 0) {
      if (array(0) ne null) {
        array = array(0).asInstanceOf[Array[AnyRef]]
        index = modulo
      } else {
        return null.asInstanceOf[A]
      }
    }
    val result = array(index).asInstanceOf[A]
    // GC purposes
    array(index) = null
    index -= 1
    result
  }

  /** Builds an iterator out of this stack. */
  def iteratorReversed: Iterator[A] =
    new Iterator[A] {
      private[this] var array = self.array
      private[this] var index = self.index

      def hasNext: Boolean =
        index > 0 || (array(0) ne null)

      def next(): A = {
        if (index == 0) {
          array = array(0).asInstanceOf[Array[AnyRef]]
          index = modulo
        }
        val result = array(index).asInstanceOf[A]
        index -= 1
        result
      }
    }
}
