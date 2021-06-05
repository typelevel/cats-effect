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
  private[this] var hashtable: Array[Throwable => Unit] = new Array(initialCapacity)
  private[this] var size: Int = 0
  private[this] var mask: Int = initialCapacity - 1
  private[this] var capacity: Int = initialCapacity

  def put(cb: Throwable => Unit, hash: Int): Unit = this.synchronized {
    val cap = capacity
    if ((size << 1) >= cap) { // the << 1 ensures that the load factor will remain between 0.25 and 0.5
      val newCap = cap << 1
      val newHashtable = new Array[Throwable => Unit](newCap)

      val table = hashtable
      var i = 0
      while (i < cap) {
        val cb = table(i)
        if (cb ne null) {
          insert(newHashtable, cb, System.identityHashCode(cb))
        }
        i += 1
      }

      hashtable = newHashtable
      mask = newCap - 1
      capacity = newCap
    }

    insert(hashtable, cb, hash)
  }

  /**
   * ''Must'' be called with the lock on the whole `ThreadSafeHashtable` object
   * already held. The `table` should contain at least one empty space to
   * place the callback in.
   */
  private[this] def insert(
      table: Array[Throwable => Unit],
      cb: Throwable => Unit,
      hash: Int): Unit = {
    var idx = hash & mask
    while (true) {
      if (table(idx) eq null) {
        table(idx) = cb
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
    val table = hashtable
    while (true) {
      if (cb eq table(idx)) {
        table(idx) = null
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

  def unsafeHashtable(): Array[Throwable => Unit] = hashtable
}

private object ThreadSafeHashtable {
  private[ThreadSafeHashtable] final val Tombstone: Throwable => Unit = _ => ()
}
