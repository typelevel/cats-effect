/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals

import java.util.concurrent.atomic.AtomicReference
import cats.effect.internals.Cancelable.{dummy, Type => Cancelable}
import scala.annotation.tailrec

/**
 * INTERNAL API — Represents a composite of functions
 * (meant for cancellation) that are stacked.
 *
 * Implementation notes:
 *
 *  1. `cancel()` is idempotent
 *  2. all methods are thread-safe / atomic
 *
 * Used in the implementation of `cats.effect.IO`. Inspired by the
 * implementation of `StackedCancelable` from the Monix library.
 */
private[effect] sealed abstract class IOConnection {
  /**
   * Cancels the unit of work represented by this reference.
   *
   * Guaranteed idempotency - calling it multiple times should have the
   * same side-effect as calling it only once. Implementations
   * of this method should also be thread-safe.
   */
  def cancel: Cancelable

  /**
   * @return true in case this cancelable hasn't been canceled,
   *         or false otherwise.
   */
  def isCanceled: Boolean

  /**
   * Pushes a cancelable reference on the stack, to be
   * popped or cancelled later in FIFO order.
   */
  def push(cancelable: Cancelable): Unit

  /**
   * Removes a cancelable reference from the stack in FIFO order.
   *
   * @return the cancelable reference that was removed.
   */
  def pop(): Cancelable
}

private[effect] object IOConnection {
  /** Builder for [[IOConnection]]. */
  def apply(): IOConnection =
    new Impl

  /**
   * Reusable [[IOConnection]] reference that is already
   * cancelled.
   */
  val alreadyCanceled: IOConnection =
    new AlreadyCanceled

  /**
   * Reusable [[IOConnection]] reference that cannot
   * be cancelled.
   */
  val uncancelable: IOConnection =
    new Uncancelable

  private final class AlreadyCanceled extends IOConnection {
    def cancel = dummy
    def isCanceled: Boolean = true
    def pop(): Cancelable = dummy
    def push(cancelable: Cancelable): Unit =
      cancelable()
  }

  private final class Uncancelable extends IOConnection {
    def cancel = dummy
    def isCanceled: Boolean = false
    def push(cancelable: Cancelable): Unit = ()
    def pop(): Cancelable = dummy
  }

  private final class Impl extends IOConnection {
    private[this] val state = new AtomicReference(List.empty[Cancelable])

    val cancel = () =>
      state.getAndSet(null) match {
        case null | Nil => ()
        case list =>
          Cancelable.cancelAll(list:_*)
      }

    def isCanceled: Boolean =
      state.get eq null

    @tailrec def push(cancelable: Cancelable): Unit =
      state.get() match {
        case null => cancelable()
        case list =>
          val update = cancelable :: list
          if (!state.compareAndSet(list, update)) push(cancelable)
      }

    @tailrec def pop(): Cancelable =
      state.get() match {
        case null | Nil => dummy
        case current @ (x :: xs) =>
          if (!state.compareAndSet(current, xs)) pop()
          else x
      }
  }
}