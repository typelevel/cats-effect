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

package cats.effect.unsafe

import cats.effect.unsafe.ref.{ReferenceQueue, WeakReference}

import scala.annotation.tailrec

import java.util.concurrent.atomic.AtomicBoolean

private final class WeakBag[A <: AnyRef] {
  import WeakBag._

  private[this] final val MaxSizePow2: Int = 1 << 30

  private[this] val queue: ReferenceQueue[A] = new ReferenceQueue()
  private[this] var capacity: Int = 256
  private[this] var table: Array[Entry[A]] = new Array(capacity)
  private[this] var index: Int = 0
  private[unsafe] val synchronizationPoint: AtomicBoolean = new AtomicBoolean(true)

  @tailrec
  def insert(a: A): Handle = {
    val oldEntry = queue.poll().asInstanceOf[Entry[A]]
    val cap = capacity
    val idx = index

    if (oldEntry ne null) {
      // There is an expired entry, and we can reuse its index in the data structure.
      val oldIndex = oldEntry.index
      val entry = new Entry(a, oldIndex, queue)
      table(oldIndex) = entry
      entry
      // We don't increment the size here because the data structure is not expanding,
      // instead we reused an old expired slot.
    } else if (idx < cap) {
      // The data structure has leftover capacity, use a new slot.
      val entry = new Entry(a, idx, queue)
      table(idx) = entry
      index += 1
      entry
    } else {
      if (cap == Int.MaxValue) {
        return () => ()
      }

      // There are no expired entries, and the table has no leftover capacity.
      // The data structure needs to grow in this case.
      val oldTable = table
      val newCap = if (cap == MaxSizePow2) Int.MaxValue else cap << 1
      val newTable = new Array[Entry[A]](newCap)
      System.arraycopy(oldTable, 0, newTable, 0, idx)
      table = newTable
      capacity = newCap
      insert(a)
    }
  }

  def forEach(f: A => Unit): Unit = {
    var i = 0
    val sz = index

    while (i < sz) {
      val a = table(i).get()
      if (a ne null) {
        f(a)
      }
      i += 1
    }
  }

  def size: Int = {
    var count = 0
    var i = 0
    val sz = index

    while (i < sz) {
      val a = table(i).get()
      if (a ne null) {
        count += 1
      }
      i += 1
    }

    count
  }
}

private[effect] object WeakBag {
  trait Handle {
    def deregister(): Unit
  }

  private[WeakBag] final class Entry[A](a: A, val index: Int, rq: ReferenceQueue[A])
      extends WeakReference[A](a, rq)
      with Handle {
    def deregister(): Unit = {
      val _ = enqueue()
    }
  }
}
