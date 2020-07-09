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

package cats
package effect
package internals

import scala.collection.immutable.LongMap

/**
 * A Map which tracks the insertion order of entries, so that entries may be
 * traversed in the order they were inserted.  Alternative to `ListMap` that
 * has better asymptotic performance at the cost of more memory usage.
 */
private[effect] class LinkedMap[K, +V](
  val entries: Map[K, (V, Long)],
  private[this] val insertionOrder: LongMap[K],
  private[this] val nextId: Long
) {

  /** Returns `true` if this map is empty, or `false` otherwise. */
  def isEmpty: Boolean =
    entries.isEmpty

  /** Returns a new map with the supplied key/value added. */
  def updated[V2 >: V](k: K, v: V2): LinkedMap[K, V2] = {
    val insertionOrderOldRemoved = entries.get(k).fold(insertionOrder) { case (_, id) => insertionOrder - id }
    new LinkedMap(entries.updated(k, (v, nextId)), insertionOrderOldRemoved.updated(nextId, k), nextId + 1)
  }

  /** Removes the element at the specified key. */
  def -(k: K): LinkedMap[K, V] =
    new LinkedMap(entries - k,
                  entries
                    .get(k)
                    .map { case (_, id) => insertionOrder - id }
                    .getOrElse(insertionOrder),
                  nextId)

  /** The keys in this map, in the order they were added. */
  def keys: Iterable[K] = insertionOrder.values

  /** The values in this map, in the order they were added. */
  def values: Iterable[V] = keys.flatMap(k => entries.get(k).toList.map(_._1))

  /** Pulls the first value from this `LinkedMap`, in FIFO order. */
  def dequeue: (V, LinkedMap[K, V]) = {
    val k = insertionOrder.head._2
    (entries(k)._1, this - k)
  }

  override def toString: String =
    keys.zip(values).mkString("LinkedMap(", ", ", ")")
}

private[effect] object LinkedMap {
  def empty[K, V]: LinkedMap[K, V] =
    emptyRef.asInstanceOf[LinkedMap[K, V]]

  private val emptyRef =
    new LinkedMap[Nothing, Nothing](Map.empty, LongMap.empty, 0)
}
