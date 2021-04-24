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

/**
 * A conceptual hash table which balances between several
 * [[ThreadSafeHashtable]]s, in order to reduce the contention on the single
 * lock by spreading it to several different locks controlling parts of the
 * hash table.
 */
private[effect] final class StripedHashtable {
  val numTables: Int = {
    val cpus = Runtime.getRuntime().availableProcessors()
    // Bit twiddling hacks.
    // http://graphics.stanford.edu/~seander/bithacks.html#RoundUpPowerOf2
    var value = cpus - 1
    value |= value >> 1
    value |= value >> 2
    value |= value >> 4
    value |= value >> 8
    value |= value >> 16
    value + 1
  }

  private[this] val mask: Int = numTables - 1

  private[this] val initialCapacity: Int = 8

  val tables: Array[ThreadSafeHashtable] = {
    val array = new Array[ThreadSafeHashtable](numTables)
    var i = 0
    while (i < numTables) {
      array(i) = new ThreadSafeHashtable(initialCapacity)
      i += 1
    }
    array
  }

  def put(cb: Throwable => Unit): Unit = {
    val hash = System.identityHashCode(cb)
    val idx = hash & mask
    tables(idx).put(cb, hash)
  }

  def remove(cb: Throwable => Unit): Unit = {
    val hash = System.identityHashCode(cb)
    val idx = hash & mask
    tables(idx).remove(cb, hash)
  }
}
