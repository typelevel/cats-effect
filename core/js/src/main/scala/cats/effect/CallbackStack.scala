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

import scala.scalajs.js

import CallbackStack.Handle

private trait CallbackStack[A] extends js.Object

private final class CallbackStackOps[A](private val callbacks: js.Array[A => Unit])
    extends AnyVal {

  @inline def push(next: A => Unit): Handle[A] = {
    callbacks.push(next)
    callbacks.length - 1
  }

  @inline def unsafeSetCallback(cb: A => Unit): Unit = {
    callbacks(callbacks.length - 1) = cb
  }

  /**
   * Invokes *all* non-null callbacks in the queue, starting with the current one. Returns true
   * iff *any* callbacks were invoked.
   */
  @inline def apply(oc: A): Boolean =
    callbacks
      .asInstanceOf[js.Dynamic]
      .reduceRight( // skips deleted indices, but there can still be nulls
        (acc: Boolean, cb: A => Unit) =>
          if (cb ne null) { cb(oc); true }
          else acc,
        false)
      .asInstanceOf[Boolean]

  /**
   * Removes the callback referenced by a handle. Returns `true` if the data structure was
   * cleaned up immediately, `false` if a subsequent call to [[pack]] is required.
   */
  @inline def clearHandle(handle: Handle[A]): Boolean = {
    // deleting an index from a js.Array makes it sparse (aka "holey"), so no memory leak
    js.special.delete(callbacks, handle)
    true
  }

  @inline def clear(): Unit =
    callbacks.length = 0 // javascript is crazy!

  @inline def pack(bound: Int): Int =
    bound - bound // aka 0, but so bound is not unused ...
}

private object CallbackStack {
  @inline def of[A](cb: A => Unit): CallbackStack[A] =
    js.Array(cb).asInstanceOf[CallbackStack[A]]

  @inline implicit def ops[A](stack: CallbackStack[A]): CallbackStackOps[A] =
    new CallbackStackOps(stack.asInstanceOf[js.Array[A => Unit]])

  type Handle[A] = Int
}
