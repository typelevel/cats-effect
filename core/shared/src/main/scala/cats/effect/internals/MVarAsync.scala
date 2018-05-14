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

package cats.effect
package internals

import java.util.concurrent.atomic.AtomicReference
import cats.effect.concurrent.MVar
import cats.effect.internals.Callback.{Type => Listener, rightUnit}
import scala.annotation.tailrec
import scala.collection.immutable.Queue

/**
 * [[MVar]] implementation for [[Async]] data types.
 */
private[effect] final class MVarAsync[F[_], A] private (
  initial: MVarAsync.State[A])(implicit F: Async[F])
  extends MVar[F, A] {

  import MVarAsync._

  private[this] val stateRef = new AtomicReference[State[A]](initial)

  def put(a: A): F[Unit] =
    F.async { cb =>
      if (unsafePut(a, cb)) cb(rightUnit)
    }

  def take: F[A] =
    F.async { cb =>
      val r = unsafeTake(cb)
      if (r != null) cb(Right(r))
    }
  
  @tailrec
  private def unsafePut(a: A, await: Listener[Unit]): Boolean = {
    if (a == null) throw new NullPointerException("null not supported in MVarAsync/MVar")
    val current: State[A] = stateRef.get

    current match {
      case _: Empty[_] =>
        if (stateRef.compareAndSet(current, WaitForTake(a, Queue.empty))) true
        else unsafePut(a, await) // retry

      case WaitForTake(value, queue) =>
        val update = WaitForTake(value, queue.enqueue(a -> await))
        if (stateRef.compareAndSet(current, update)) false
        else unsafePut(a, await) // retry

      case current @ WaitForPut(first, _) =>
        if (stateRef.compareAndSet(current, current.dequeue)) { first(Right(a)); true }
        else unsafePut(a, await) // retry
    }
  }

  @tailrec
  private def unsafeTake(await: Listener[A]): A = {
    val current: State[A] = stateRef.get
    current match {
      case _: Empty[_] =>
        if (stateRef.compareAndSet(current, WaitForPut(await, Queue.empty)))
          null.asInstanceOf[A]
        else
          unsafeTake(await) // retry

      case WaitForTake(value, queue) =>
        if (queue.isEmpty) {
          if (stateRef.compareAndSet(current, State.empty))
            value
          else
            unsafeTake(await)
        }
        else {
          val ((ax, notify), xs) = queue.dequeue
          if (stateRef.compareAndSet(current, WaitForTake(ax, xs))) {
            notify(rightUnit) // notification
            value
          } else {
            unsafeTake(await) // retry
          }
        }

      case WaitForPut(first, queue) =>
        if (stateRef.compareAndSet(current, WaitForPut(first, queue.enqueue(await))))
          null.asInstanceOf[A]
        else
          unsafeTake(await)
    }
  }
}

private[effect] object MVarAsync {
  /** Builds an [[MVarAsync]] instance with an `initial` value. */
  def apply[F[_], A](initial: A)(implicit F: Async[F]): MVar[F, A] =
    new MVarAsync[F, A](State(initial))

  /** Returns an empty [[MVarAsync]] instance. */
  def empty[F[_], A](implicit F: Async[F]): MVar[F, A] =
    new MVarAsync[F, A](State.empty)

  /** ADT modelling the internal state of `MVar`. */
  private sealed trait State[A]

  /** Private [[State]] builders.*/
  private object State {
    private[this] val ref = Empty()
    def apply[A](a: A): State[A] = WaitForTake(a, Queue.empty)
    /** `Empty` state, reusing the same instance. */
    def empty[A]: State[A] = ref.asInstanceOf[State[A]]
  }

  /**
   * `MVarAsync` state signaling an empty location.
   *
   * Evolves into [[WaitForPut]] or [[WaitForTake]],
   * depending on which operation happens first.
   */
  private final case class Empty[A]() extends State[A]

  /**
   * `MVarAsync` state signaling it has `take` callbacks
   * registered and we are waiting for one or multiple
   * `put` operations.
   *
   * @param first is the first request waiting in line
   * @param queue are the rest of the requests waiting in line,
   *        if more than one `take` requests were registered
   */
  private final case class WaitForPut[A](first: Listener[A], queue: Queue[Listener[A]])
    extends State[A] {

    def dequeue: State[A] =
      if (queue.isEmpty) State.empty[A] else {
        val (x, xs) = queue.dequeue
        WaitForPut(x, xs)
      }
  }

  /**
   * `MVarAsync` state signaling it has one or more values enqueued,
   * to be signaled on the next `take`.
   *
   * @param value is the first value to signal
   * @param queue are the rest of the `put` requests, along with the
   *        callbacks that need to be called whenever the corresponding
   *        value is first in line (i.e. when the corresponding `put`
   *        is unblocked from the user's point of view)
   */
  private final case class WaitForTake[A](value: A, queue: Queue[(A, Listener[Unit])])
    extends State[A]
}

