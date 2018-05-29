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
import cats.effect.util.CompositeException

import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
 * INTERNAL API — Represents a composite of functions
 * (meant for cancellation) that are stacked.
 *
 * Implementation notes:
 *
 *  - `cancel()` is idempotent
 *  - all methods are thread-safe / atomic
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
   * popped or canceled later in FIFO order.
   */
  def push(cancelable: Cancelable): Unit

  /**
   * Pushes a pair of `IOConnection` on the stack, which on
   * cancellation will get trampolined.
   *
   * This is useful in `IO.race` for example, because combining
   * a whole collection of `IO` tasks, two by two, can lead to
   * building a cancelable that's stack unsafe.
   */
  def pushPair(lh: IOConnection, rh: IOConnection): Unit

  /**
   * Removes a cancelable reference from the stack in FIFO order.
   *
   * @return the cancelable reference that was removed.
   */
  def pop(): Cancelable

  /**
   * Tries to reset an `IOConnection`, from a cancelled state,
   * back to a pristine state, but only if possible.
   *
   * Returns `true` on success, or `false` if there was a race
   * condition (i.e. the connection wasn't cancelled) or if
   * the type of the connection cannot be reactivated.
   */
  def tryReactivate(): Boolean
}

private[effect] object IOConnection {
  /** Builder for [[IOConnection]]. */
  def apply(): IOConnection =
    new Impl

  /**
   * Reusable [[IOConnection]] reference that is already
   * canceled.
   */
  val alreadyCanceled: IOConnection =
    new AlreadyCanceled

  /**
   * Reusable [[IOConnection]] reference that cannot
   * be canceled.
   */
  val uncancelable: IOConnection =
    new Uncancelable

  private final class AlreadyCanceled extends IOConnection {
    def cancel = dummy
    def isCanceled: Boolean = true
    def pop(): Cancelable = dummy
    def push(cancelable: Cancelable): Unit = cancelable()
    def tryReactivate(): Boolean = false
    def pushPair(lh: IOConnection, rh: IOConnection): Unit =
      try lh.cancel() finally rh.cancel()
  }

  private final class Uncancelable extends IOConnection {
    def cancel = dummy
    def isCanceled: Boolean = false
    def push(cancelable: Cancelable): Unit = ()
    def pushCollection(cancelables: Cancelable*): Unit = ()
    def pop(): Cancelable = dummy
    def tryReactivate(): Boolean = true
    def pushPair(lh: IOConnection, rh: IOConnection): Unit = ()
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

    def pushPair(lh: IOConnection, rh: IOConnection): Unit =
      push(new TrampolinedPair(lh, rh))

    @tailrec def pop(): Cancelable =
      state.get() match {
        case null | Nil => dummy
        case current @ (x :: xs) =>
          if (!state.compareAndSet(current, xs)) pop()
          else x
      }

    def tryReactivate(): Boolean =
      state.compareAndSet(null, Nil)
  }

  private final class TrampolinedPair(lh: IOConnection, rh: IOConnection)
    extends Cancelable.Type with Runnable {

    override def run(): Unit =
      Cancelable.cancelAll(lh.cancel, rh.cancel)

    def apply(): Unit = {
      // Needs to be trampolined, otherwise it can cause StackOverflows
      TrampolineEC.immediate.execute(this)
    }
  }
}
