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

import cats.effect.unsafe.IterableWeakMap._

import scala.collection.mutable
import scala.scalajs.js

/**
 * Although JS provides a native `WeakMap` implementation with weakly-referenced keys, it lacks
 * the capability to iterate over the keys in the map which we required for fiber dumps.
 *
 * Here, we implement an `IterableWeakMap` that also keeps track of its keys for subsequent
 * iteration, without creating strong references to the keys or otherwise leaking memory. It
 * requires two features only standardized in ES2021, `WeakRef` and `FinalizationRegistry`,
 * although already implemented by many runtimes, as well as `WeakMap` from ES6 (aka ES2015).
 *
 * This implementation is adapted from an example provided in the
 * [[https://github.com/tc39/proposal-weakrefs ECMA `WeakRef` proposal]]. Because we do not need
 * a fully-featured map (e.g., no remove method) we were able to make additional optimizations.
 */
private[unsafe] class IterableWeakMap[K, V] {

  // The underlying weak map
  private[this] val weakMap = new WeakMap[K, V]

  // A set of weak refs to all the keys in the map, for iteration
  private[this] val refSet = mutable.Set[js.WeakRef[K]]()

  // A mechanism to register finalizers for when the keys are GCed
  // Comparable to https://docs.oracle.com/javase/9/docs/api/java/lang/ref/Cleaner.html
  private[this] val finalizationGroup =
    new js.FinalizationRegistry[K, Finalizer[K], js.WeakRef[K]](_.cleanup())

  def set(key: K, value: V): Unit = {
    val ref = new js.WeakRef(key)

    // Store the key-value pair
    weakMap.set(key, value)

    // Add a weak ref to the key to our set
    refSet.add(ref)

    // Register a finalizer to remove the ref from the set when the key is GCed
    finalizationGroup.register(key, Finalizer(refSet, ref))
  }

  def get(key: K): Option[V] = weakMap.get(key).toOption

  def entries(): Iterator[(K, V)] =
    refSet.iterator.flatMap { ref =>
      (for {
        key <- ref.deref()
        value <- weakMap.get(key)
      } yield (key, value)).toOption
    }

}

private[unsafe] object IterableWeakMap {
  private[this] final val Undefined = "undefined"

  /**
   * Feature-tests for all the required, well, features :)
   */
  def isAvailable: Boolean =
    js.typeOf(js.Dynamic.global.WeakMap) != Undefined &&
      js.typeOf(js.Dynamic.global.WeakRef) != Undefined &&
      js.typeOf(js.Dynamic.global.FinalizationRegistry) != Undefined

  /**
   * A finalizer to run when a key is GCed. Removes a weak ref to the key from a set.
   */
  private final case class Finalizer[K](set: mutable.Set[js.WeakRef[K]], ref: js.WeakRef[K]) {
    def cleanup(): Unit = {
      set.remove(ref)
      ()
    }
  }
}
