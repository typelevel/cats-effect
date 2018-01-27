/*
 * Copyright 2017 Typelevel
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

import cats.effect.util.CompositeException

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
private[effect] sealed abstract class Connection {
  /**
   * Cancels the unit of work represented by this reference.
   *
   * Guaranteed idempotency - calling it multiple times should have the
   * same side-effect as calling it only once. Implementations
   * of this method should also be thread-safe.
   */
  def cancel: () => Unit

  /**
   * @return true in case this cancelable hasn't been canceled,
   *         or false otherwise.
   */
  def isCanceled: Boolean

  /**
   * Pushes a cancelable reference on the stack, to be
   * popped or cancelled later in FIFO order.
   */
  def push(cancelable: () => Unit): Unit

  /**
   * Removes a cancelable reference from the stack in FIFO order.
   *
   * @return the cancelable reference that was removed.
   */
  def pop(): () => Unit
}

private[effect] object Connection {
  /** Builder for [[Connection]]. */
  def apply(): Connection =
    new Impl

  /**
   * Reusable [[Connection]] reference that is already
   * cancelled.
   */
  val alreadyCanceled: Connection =
    new AlreadyCanceled

  /**
   * Reusable [[Connection]] reference that cannot
   * be cancelled.
   */
  val uncancelable: Connection =
    new Uncancelable

  /** Reusable no-op function reference. */
  final val dummy: () => Unit =
    () => ()

  private final class AlreadyCanceled extends Connection {
    def cancel = dummy
    def isCanceled: Boolean = true
    def pop(): () => Unit = dummy
    def push(cancelable: () => Unit): Unit =
      cancelable()
  }

  private final class Uncancelable extends Connection {
    def cancel = dummy
    def isCanceled: Boolean = false
    def push(cancelable: () => Unit): Unit = ()
    def pop(): () => Unit = dummy
  }

  private final class Impl extends Connection {
    private[this] val state = new AtomicReference(List.empty[() => Unit])

    val cancel = () =>
      state.getAndSet(null) match {
        case null | Nil => ()
        case list => cancelAll(list)
      }

    def isCanceled: Boolean =
      state.get eq null

    @tailrec def push(cancelable: () => Unit): Unit =
      state.get() match {
        case null => cancelable()
        case list =>
          val update = cancelable :: list
          if (!state.compareAndSet(list, update)) push(cancelable)
      }

    @tailrec def pop(): () => Unit =
      state.get() match {
        case null | Nil => dummy
        case current @ (x :: xs) =>
          if (!state.compareAndSet(current, xs)) pop()
          else x
      }
  }

  private def cancelAll(seq: List[() => Unit]): Unit = {
    var errors = List.empty[Throwable]
    val cursor = seq.iterator
    while (cursor.hasNext) {
      try cursor.next().apply()
      catch { case ex if NonFatal(ex) => errors = ex :: errors }
    }

    errors match {
      case Nil => ()
      case x :: Nil => throw x
      case first :: second :: rest =>
        throw CompositeException(first, second, rest)
    }
  }
}