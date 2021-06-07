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

package cats.effect.tracing

import cats.effect.unsafe.Hashing

private final class ThreadSafeHashMap(initialCapacity: Int) {
  private[this] var keysTable: Array[Class[_]] = new Array(initialCapacity)
  private[this] var valsTable: Array[TracingEvent] = new Array(initialCapacity)
  private[this] var size: Int = 0
  private[this] var mask: Int = initialCapacity - 1
  private[this] var capacity: Int = initialCapacity
  private[this] val log2NumTables: Int = Hashing.log2NumTables
  private[this] val Tombstone: Class[_] = ThreadSafeHashMap.Tombstone

  def put(cls: Class[_], event: TracingEvent, hash: Int): Unit = this.synchronized {
    val sz = size
    val cap = capacity
    if ((sz << 4) / 3 >= cap) {
      val newCap = cap << 1
      val newMask = newCap - 1
      val newKeysTable = new Array[Class[_]](newCap)
      val newValsTable = new Array[TracingEvent](newCap)

      val kt = keysTable
      val vt = valsTable
      var i = 0
      while (i < cap) {
        val c = kt(i)
        if (c ne null) {
          insert(
            newKeysTable,
            newValsTable,
            newMask,
            c,
            vt(i),
            System.identityHashCode(c) >> log2NumTables)
        }
        i += 1
      }

      keysTable = newKeysTable
      valsTable = newValsTable
      mask = newMask
      capacity = newCap
    }

    insert(keysTable, valsTable, mask, cls, event, hash)
    size = sz + 1
  }

  /**
   * ''Must'' be called with the lock on the whole `ThreadSafeHashMap` object
   * already held. The `table` should contain at least one empty space to
   * place the callback in.
   */
  private[this] def insert(
      keysTable: Array[Class[_]],
      valsTable: Array[TracingEvent],
      mask: Int,
      cls: Class[_],
      event: TracingEvent,
      hash: Int
  ): Unit = {
    var idx = hash & mask
    var remaining = mask

    while (remaining >= 0) {
      val cur = keysTable(idx)
      if ((cur eq null) || (cur eq Tombstone)) {
        keysTable(idx) = cls
        valsTable(idx) = event
        return
      } else {
        idx = (idx + 1) & mask
      }
      remaining -= 1
    }
  }

  def get(cls: Class[_], hash: Int): TracingEvent = {
    val msk = mask
    val init = hash & msk
    var idx = init
    val kt = keysTable
    var remaining = msk

    while (remaining >= 0) {
      val cur = kt(idx)
      if (cls eq cur) {
        return valsTable(idx)
      } else if (cur ne null) {
        idx = (idx + 1) & msk
      } else {
        return null
      }
      remaining -= 1
    }

    null
  }

  def remove(cls: Class[_], hash: Int): Unit = this.synchronized {
    val init = hash & mask
    var idx = init
    val kt = keysTable
    var remaining = mask

    while (remaining >= 0) {
      val cur = kt(idx)
      if (cls eq cur) {
        kt(idx) = Tombstone
        valsTable(idx) = null
        size -= 1
        return
      } else if (cur ne null) {
        idx = (idx + 1) & mask
      } else {
        return
      }
      remaining -= 1
    }
  }
}

private object ThreadSafeHashMap {
  private[ThreadSafeHashMap] final val Tombstone: Class[_] = ThreadSafeHashMap.getClass
}
