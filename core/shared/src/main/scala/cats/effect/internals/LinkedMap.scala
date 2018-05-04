/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2013-2018 Paul Chiusano, and respective contributors 
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
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
private[effect] class LinkedMap[K, +V](val entries: Map[K, (V, Long)],
                                           private[this] val insertionOrder: LongMap[K],
                                           private[this] val nextId: Long) {

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

  override def toString = keys.zip(values).mkString("LinkedMap(", ", ", ")")
}

private[effect] object LinkedMap {
  def empty[K, V]: LinkedMap[K, V] =
    new LinkedMap[K, V](Map.empty, LongMap.empty, 0)
}
