/*
 * Copyright 2020-2021 Typelevel
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

import java.util.{Collection, Iterator}

private final class FiberArrayList(capacity: Int) extends Collection[IOFiber[_]] {

  import LocalQueueConstants._

  private[this] val list: Array[IOFiber[_]] = new Array(capacity)

  private[this] var offset: Int = FiberArrayBaseOffset

  private[this] var listSize: Int = FiberArrayBaseOffset

  private[this] val it: Iterator[IOFiber[_]] =
    new Iterator[IOFiber[_]] {
      def hasNext(): Boolean = offset < listSize
      def next(): IOFiber[_] = {
        val fiber = Unsafe.getObject(list, offset).asInstanceOf[IOFiber[_]]
        offset += ReferencePointerSize
        fiber
      }
    }

  def +=(fiber: IOFiber[_]): Unit = {
    Unsafe.putObject(list, offset, fiber)
    offset += ReferencePointerSize
    listSize += ReferencePointerSize
  }

  def reset(): Unit = {
    offset = FiberArrayBaseOffset
    listSize = FiberArrayBaseOffset
    System.arraycopy(NullArray, 0, list, 0, capacity)
  }

  def size(): Int = capacity

  def isEmpty(): Boolean = false

  def contains(x: Object): Boolean = false

  def iterator(): Iterator[IOFiber[_]] = {
    offset = FiberArrayBaseOffset
    it
  }

  def toArray(): Array[Object] = null

  def toArray[T](x: Array[T with Object]): Array[T with Object] = null

  def add(x: IOFiber[_]): Boolean = false

  def remove(x: Object): Boolean = false

  def containsAll(x: Collection[_]): Boolean = false

  def addAll(x: Collection[_ <: IOFiber[_]]): Boolean = false

  def removeAll(x: Collection[_]): Boolean = false

  def retainAll(x: Collection[_]): Boolean = false

  def clear(): Unit = ()
}
