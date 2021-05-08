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

/**
 * A primitive thread safe hash table implementation specialized for a single
 * purpose, to hold references to the error callbacks of fibers. The hashing
 * function is [[System.identityHashCode]] simply because the callbacks are
 * functions and therefore have no defined notion of [[Object#hashCode]]. The
 * thread safety is achieved by pessimistically locking the whole structure.
 * This is fine in practice because this data structure is only accessed when
 * running [[cats.effect.IO#unsafeRunFiber]], which is not expected to be
 * executed often in a realistic system.
 *
 * @param initialCapacity the initial capacity of the hashtable, ''must'' be a
 *                        power of 2
 */
private[effect] final class ThreadSafeHashtable(initialCapacity: Int) {
  var hashtable: Array[Throwable => Unit] = new Array(initialCapacity)
  private[this] var size = 0
  private[this] var mask = initialCapacity - 1
  private[this] var capacity = initialCapacity

  def put(cb: Throwable => Unit, hash: Int): Unit = this.synchronized {
    val cap = capacity
    if (size << 1 >= cap) { // the << 1 ensures that the load factor will remain between 0.25 and 0.5
      val newCap = cap * 2
      val newHashtable = new Array[Throwable => Unit](newCap)
      System.arraycopy(hashtable, 0, newHashtable, 0, cap)
      hashtable = newHashtable
      mask = newCap - 1
      capacity = newCap
    }

    var idx = hash & mask
    while (true) {
      if (hashtable(idx) == null) {
        hashtable(idx) = cb
        size += 1
        return
      } else {
        idx += 1
        idx &= mask
      }
    }
  }

  def remove(cb: Throwable => Unit, hash: Int): Unit = this.synchronized {
    val init = hash & mask
    var idx = init
    while (true) {
      if (cb eq hashtable(idx)) {
        hashtable(idx) = null
        size -= 1
        return
      } else {
        idx += 1
        idx &= mask
        if (idx == init) {
          return
        }
      }
    }
  }
}
