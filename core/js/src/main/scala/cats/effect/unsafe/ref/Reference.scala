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

/*
 * scalajs-weakreferences (https://github.com/scala-js/scala-js-weakreferences)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package cats.effect.unsafe.ref

import scala.scalajs.js

/* The JavaDoc says that the methods are concrete in `Reference`, and not
 * overridden in `WeakReference`. To mimic this setup, and since
 * `WeakReference` is the only implemented subclass, we actually implement the
 * behavior of `WeakReference` in `Reference`.
 */
private[unsafe] abstract class Reference[T] private[ref] (
    referent: T,
    queue: ReferenceQueue[_ >: T]) {
  private[this] var weakRef = new js.WeakRef(referent)
  var enqueued: Boolean = false

  if (queue != null)
    queue.register(this, referent)

  def get(): T =
    if (weakRef == null) null.asInstanceOf[T]
    else weakRef.deref().getOrElse(null.asInstanceOf[T])

  def clear(): Unit = {
    if (queue != null)
      queue.unregister(this)
    weakRef = null
  }

  def isEnqueued(): Boolean =
    enqueued

  def enqueue(): Boolean = {
    if (queue != null && queue.enqueue(this)) {
      queue.unregister(this)
      true
    } else {
      false
    }
  }
}
