/*
 * Copyright 2020-2022 Typelevel
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

private trait CallbackStack[A] extends js.Object

private final class CallbackStackOps[A](private val callbacks: js.Array[A => Unit])
    extends AnyVal {

  @inline def push(next: A => Unit): CallbackStack[A] = {
    callbacks.push(next)
    callbacks.asInstanceOf[CallbackStack[A]]
  }

  @inline def unsafeSetCallback(cb: A => Unit): Unit = {
    callbacks(callbacks.length - 1) = cb
  }

  /**
   * Invokes *all* non-null callbacks in the queue, starting with the current one. Returns true
   * iff *any* callbacks were invoked.
   */
  @inline def apply(oc: A, _invoked: Boolean): Boolean = {
    var i = callbacks.length - 1
    var invoked = _invoked
    while (i >= 0) {
      val cb = callbacks(i)
      if (cb ne null) {
        cb(oc)
        invoked = true
      }
      i -= 1
    }
    invoked
  }

  /**
   * Removes the current callback from the queue.
   */
  @inline def clearCurrent(handle: CallbackStack.Handle): Unit = callbacks(handle) = null

  @inline def currentHandle(): CallbackStack.Handle = callbacks.length - 1

  @inline def clear(): Unit =
    callbacks.length = 0 // javascript is crazy!
}

private object CallbackStack {
  @inline def apply[A](cb: A => Unit): CallbackStack[A] =
    js.Array(cb).asInstanceOf[CallbackStack[A]]

  @inline implicit def ops[A](stack: CallbackStack[A]): CallbackStackOps[A] =
    new CallbackStackOps(stack.asInstanceOf[js.Array[A => Unit]])

  type Handle = Int
}
