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

package cats.effect

import scala.annotation.tailrec

import java.util.concurrent.atomic.AtomicReference

private[effect] final class CallbackStack[A](private[this] var callback: OutcomeIO[A] => Unit)
    extends AtomicReference[CallbackStack[A]] {

  @tailrec
  def push(next: OutcomeIO[A] => Unit): CallbackStack[A] = {
    val cur = get()
    val attempt = new CallbackStack(next)
    attempt.set(cur)

    if (!compareAndSet(cur, attempt))
      push(next)
    else
      attempt
  }

  /**
   * Invokes *all* non-null callbacks in the queue, starting with the current one.
   */
  @tailrec
  def apply(oc: OutcomeIO[A]): Unit = {
    if (callback != null) {
      callback(oc)
    }

    val next = get()
    if (next != null) {
      next(oc)
    }
  }

  /**
   * Removes the current callback from the queue.
   */
  def clearCurrent(): Unit = callback = null
}
