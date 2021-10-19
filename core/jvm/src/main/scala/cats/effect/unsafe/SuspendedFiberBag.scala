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

import scala.annotation.nowarn
import scala.collection.mutable.ArrayBuffer

import java.lang.ref.WeakReference
import java.util.{ConcurrentModificationException, Collections, Map, WeakHashMap}
import java.util.concurrent.ThreadLocalRandom

/**
 * A slightly more involved implementation of an unordered bag used for tracking asynchronously
 * suspended fiber instances on the JVM. This bag is backed by an array of synchronized
 * `java.util.WeakHashMap` instances. This decision is based on several factors:
 *   1. A `java.util.WeakHashMap` is used because we want the resumed fibers to be automatically
 *      removed from the hash map data structure by the GC, whenever their keys expire (which is
 *      right around their resumption).
 *   1. `java.util.WeakHashMap` is **not** thread safe by nature. In the official javadoc for
 *      this class it is recommended that an instance be wrapped in
 *      `java.util.Collections.synchronizedMap` before writing to the hash map from different
 *      threads. This is absolutely crucial in our use case, because fibers can be carried by
 *      any thread (including threads external to the compute thread pool, e.g. when using
 *      `IO#evalOn`).
 *   1. Because `java.util.Collections.synchronizedMap` is a simple wrapper around any map which
 *      just synchronizes the access to the map through the built in JVM `synchronized`
 *      mechanism, we need several instances of these synchronized `WeakHashMap`s just to reduce
 *      contention between threads. A particular instance is selected using a thread local
 *      source of randomness using an instance of `java.util.concurrent.ThreadLocalRandom`.
 *
 * @note
 *   The `unmonitor` method is a no-op, but it needs to exist to keep source compatibility with
 *   Scala.js. The removal of a resumed fiber is done automatically by the GC.
 */
private[effect] final class SuspendedFiberBag {

  private[this] val size: Int = Runtime.getRuntime().availableProcessors() << 2
  private[this] val bags: Array[Map[AnyRef, WeakReference[IOFiber[_]]]] =
    new Array(size)

  {
    var i = 0
    while (i < size) {
      bags(i) = Collections.synchronizedMap(new WeakHashMap())
      i += 1
    }
  }

  /**
   * Registers a suspended fiber, tracked by the provided key which is an opaque object which
   * uses reference equality for comparison.
   *
   * @param key
   *   an opaque identifier for the suspended fiber
   * @param fiber
   *   the suspended fiber to be registered
   */
  def monitor(key: AnyRef, fiber: IOFiber[_]): Unit = {
    val rnd = ThreadLocalRandom.current()
    val idx = rnd.nextInt(size)
    bags(idx).put(key, new WeakReference(fiber))
    ()
  }

  /**
   * Deregisters a resumed fiber, tracked by the provided key which is an opaque object which
   * uses reference equality for comparison.
   *
   * @param key
   *   an opaque identifier for the resumed fiber
   */
  @nowarn("cat=unused-params")
  def unmonitor(key: AnyRef): Unit = {
    // no-op
  }

  def contents(): Set[IOFiber[_]] = {
    try {
      val buffer = new ArrayBuffer[IOFiber[_]]
      bags foreach { bag =>
        if (bag != null) {
          val itr = bag.values().iterator()
          while (itr.hasNext()) {
            buffer += itr.next().get()
          }
        }
      }

      buffer.toSet - null
    } catch {
      case _: ConcurrentModificationException =>
        contents()
    }
  }
}
