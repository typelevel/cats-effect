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

package cats.effect.unsafe

import java.lang.ref.{ReferenceQueue, WeakReference}

private final class WeakDataStructure[K <: AnyRef, V <: AnyRef] {
  import WeakDataStructure._

  private[this] final val MaxSizePow2: Int = 1 << 30

  private[this] val queue: ReferenceQueue[K] = new ReferenceQueue()

  private[this] var capacity: Int = 256
  private[this] var table: Array[WeakEntry[K, V]] = new Array(capacity)
  private[this] var size = 0

  def insert(key: K, value: V): Unit = {
    val oldEntry = queue.poll().asInstanceOf[WeakEntry[K, V]]
    val cap = capacity
    val sz = size

    if (oldEntry ne null) {
      // There is an expired entry, and we can reuse its index in the data structure.
      val index = oldEntry.index
      table(index) = new WeakEntry(key, value, index, queue)
      // We don't increment the size here because the data structure is not growing,
      // instead we reused an old expired slot.
    } else if (sz < cap) {
      // The data structure has leftover capacity, use a new slot.
      table(sz) = new WeakEntry(key, value, sz, queue)
      size += 1
    } else {
      // There are no expired entries, and the table has no leftover capacity.
      // The data structure needs to grow in this case.
      val oldTable = table

      if (cap == Int.MaxValue) {
        return
      }

      val newCap = if (cap == MaxSizePow2) Int.MaxValue else cap << 1
      val newTable = new Array[WeakEntry[K, V]](newCap)
      System.arraycopy(oldTable, 0, newTable, 0, size)
      table = newTable
      capacity = newCap

      table(sz) = new WeakEntry(key, value, sz, queue)
      size += 1
    }
  }

  def valueSet: Set[V] =
    table
      .toSet[WeakEntry[K, V]]
      .filter(_ ne null)
      .filter(_.get() ne null)
      .map(_.valueRef.get())
      .filter(_ ne null)

  def length: Int =
    table.toSet[WeakEntry[K, V]].count { we =>
      (we ne null) && (we.get() ne null) && (we.valueRef.get() ne null)
    }
}

private object WeakDataStructure {
  private[WeakDataStructure] final class WeakEntry[K, V](
      key: K,
      value: V,
      val index: Int,
      rq: ReferenceQueue[K])
      extends WeakReference[K](key, rq) {
    val valueRef: WeakReference[V] = new WeakReference(value)
  }
}
