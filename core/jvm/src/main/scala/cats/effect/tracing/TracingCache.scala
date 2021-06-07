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

/**
 * Global cache for trace frames. Keys are references to lambda classes.
 * Should converge to the working set of traces very quickly for hot code paths.
 */
private final class TracingCache extends ClassValue[TracingEvent] {
  private[this] val log2NumTables: Int = Hashing.log2NumTables

  private[this] def numTables: Int = 1 << log2NumTables

  private[this] val mask: Int = numTables - 1

  val tables: Array[ThreadSafeHashMap] = {
    val array = new Array[ThreadSafeHashMap](numTables)
    var i = 0
    while (i < numTables) {
      array(i) = new ThreadSafeHashMap(32)
      i += 1
    }
    array
  }

  override protected def computeValue(cls: Class[_]): TracingEvent = ???

  override def get(cls: Class[_]): TracingEvent = {
    val hash = System.identityHashCode(cls)
    val idx = hash & mask
    val clsHash = hash >> log2NumTables
    val cached = tables(idx).get(cls, clsHash)
    if (cached eq null) {
      buildEvent(cls, idx, clsHash)
    } else {
      cached
    }
  }

  private[this] def buildEvent(cls: Class[_], idx: Int, hash: Int): TracingEvent = {
    val callSite = CallSite.generateCallSite()
    val event = TracingEvent.CallSite(callSite)
    tables(idx).put(cls, event, hash)
    event
  }

  override def remove(cls: Class[_]): Unit = {
    val hash = System.identityHashCode(cls)
    val idx = hash & mask
    tables(idx).remove(cls, hash >> log2NumTables)
  }
}
