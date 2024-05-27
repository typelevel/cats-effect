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
package unsafe

/**
 * A primitive thread safe hash table implementation specialized for a single purpose, to hold
 * references to the error callbacks of fibers. The hashing function is
 * [[System.identityHashCode]] simply because the callbacks are functions and therefore have no
 * defined notion of [[Object#hashCode]]. The thread safety is achieved by pessimistically
 * locking the whole structure. This is fine in practice because this data structure is only
 * accessed when running [[cats.effect.IO#unsafeRunFiber]], which is not expected to be executed
 * often in a realistic system.
 *
 * @param initialCapacity
 *   the initial capacity of the hashtable, ''must'' be a power of 2
 */
private[effect] final class ThreadSafeHashtable[A <: AnyRef](
    private[this] val initialCapacity: Int) {
  private[this] var hashtable: Array[AnyRef] = new Array(initialCapacity)
  private[this] var size: Int = 0
  private[this] var mask: Int = initialCapacity - 1
  private[this] var capacity: Int = initialCapacity
  private[this] val log2NumTables: Int = StripedHashtable.log2NumTables
  private[this] val Tombstone: AnyRef = ThreadSafeHashtable.Tombstone

  def put(a: A, hash: Int): Unit = this.synchronized {
    val sz = size
    val cap = capacity
    if ((sz << 1) >= cap) { // the << 1 ensures that the load factor will remain between 0.25 and 0.5
      val newCap = cap << 1
      val newMask = newCap - 1
      val newHashtable = new Array[AnyRef](newCap)

      val table = hashtable
      var i = 0
      while (i < cap) {
        val cur = table(i)
        if ((cur ne null) && (cur ne Tombstone)) {
          // Only re-insert references to actual callbacks.
          // Filters out `Tombstone`s.
          insert(newHashtable, newMask, cur, System.identityHashCode(cur) >> log2NumTables)
        }
        i += 1
      }

      hashtable = newHashtable
      mask = newMask
      capacity = newCap
    }

    insert(hashtable, mask, a, hash)
    size = sz + 1
  }

  /**
   * ''Must'' be called with the lock on the whole `ThreadSafeHashtable` object already held.
   * The `table` should contain at least one empty space to place the callback in.
   */
  private[this] def insert(table: Array[AnyRef], mask: Int, a: AnyRef, hash: Int): Unit = {
    var idx = hash & mask
    var remaining = mask

    while (remaining >= 0) {
      val cur = table(idx)
      if ((cur eq null) || (cur eq Tombstone)) {
        // Both null and `Tombstone` references are considered empty and new
        // references can be inserted in their place.
        table(idx) = a
        return
      } else {
        idx = (idx + 1) & mask
      }
      remaining -= 1
    }
  }

  def remove(a: A, hash: Int): Unit = this.synchronized {
    val msk = mask
    val init = hash & msk
    var idx = init
    val table = hashtable
    var remaining = msk

    while (remaining >= 0) {
      val cur = table(idx)
      if (a eq cur) {
        // Mark the removed callback with the `Tombstone` reference.
        table(idx) = Tombstone
        size -= 1

        val sz = size
        val cap = capacity
        if (cap > initialCapacity && (sz << 2) < cap) {
          // halve the capacity of the table if it has been filled with less
          // than 1/4 of the capacity
          val newCap = cap >>> 1
          val newMask = newCap - 1
          val newHashtable = new Array[AnyRef](newCap)

          val table = hashtable
          var i = 0
          while (i < cap) {
            val cur = table(i)
            if ((cur ne null) && (cur ne Tombstone)) {
              // Only re-insert references to actual callbacks.
              // Filters out `Tombstone`s.
              insert(newHashtable, newMask, cur, System.identityHashCode(cur) >> log2NumTables)
            }
            i += 1
          }

          hashtable = newHashtable
          mask = newMask
          capacity = newCap
        }

        return
      } else if (cur ne null) {
        // Skip over references of other callbacks and `Tombstone` objects.
        idx = (idx + 1) & msk
      } else {
        // Reached a `null` reference. The callback was not in the hash table.
        return
      }
      remaining -= 1
    }
  }

  def unsafeHashtable(): Array[AnyRef] = hashtable

  /*
   * Only used in testing.
   */

  private[unsafe] def isEmpty: Boolean =
    size == 0 && hashtable.forall(cb => (cb eq null) || (cb eq Tombstone))

  private[unsafe] def unsafeCapacity(): Int = capacity

  private[unsafe] def unsafeInitialCapacity(): Int = initialCapacity
}

private object ThreadSafeHashtable {

  /**
   * Sentinel object for marking removed callbacks. Used to keep the linear probing chain
   * intact.
   */
  private[ThreadSafeHashtable] final val Tombstone: AnyRef = new AnyRef()
}
