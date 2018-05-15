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
import cats.effect.internals.Callback.rightUnit
import scala.annotation.tailrec
import scala.collection.immutable.Queue

/**
 * [[MVar]] implementation for [[Async]] data types.
 */
private[effect] final class MVarAsync[F[_], A] private (
  initial: MVarAsync.State[A])(implicit F: Async[F])
  extends MVar[F, A] {

  import MVarAsync._

  /** Shared mutable state. */
  private[this] val stateRef = new AtomicReference[State[A]](initial)

  def put(a: A): F[Unit] =
    F.async(unsafePut(a))

  def take: F[A] =
    F.async(unsafeTake)

  def read: F[A] =
    F.async(unsafeRead)

  private def streamAll(value: Either[Nothing, A], listeners: Iterable[Listener[A]]): Unit = {
    val cursor = listeners.iterator
    while (cursor.hasNext)
      cursor.next().apply(value)
  }

  @tailrec
  private def unsafePut(a: A)(onPut: Listener[Unit]): Unit = {
    stateRef.get match {
      case current @ WaitForTake(value, puts) =>
        val update = WaitForTake(value, puts.enqueue(a -> onPut))
        if (!stateRef.compareAndSet(current, update)) {
          // $COVERAGE-OFF$
          unsafePut(a)(onPut) // retry
          // $COVERAGE-ON$
        }

      case current @ WaitForPut(reads, takes) =>
        var first: Listener[A] = null
        val update: State[A] =
          if (takes.isEmpty) State(a) else {
            val (x, rest) = takes.dequeue
            first = x
            if (rest.isEmpty) State.empty[A]
            else WaitForPut(Queue.empty, rest)
          }

        if (!stateRef.compareAndSet(current, update)) {
          // $COVERAGE-OFF$
          unsafePut(a)(onPut) // retry
          // $COVERAGE-ON$
        } else {
          val value = Right(a)
          // Satisfies all current `read` requests found
          streamAll(value, reads)
          // Satisfies the first `take` request found
          if (first ne null) first(value)
          // Signals completion of `put`
          onPut(rightUnit)
        }
    }
  }

  @tailrec
  private def unsafeTake(onTake: Listener[A]): Unit = {
    val current: State[A] = stateRef.get
    current match {
      case WaitForTake(value, queue) =>
        if (queue.isEmpty) {
          if (stateRef.compareAndSet(current, State.empty))
            // Signals completion of `take`
            onTake(Right(value))
          else {
            // $COVERAGE-OFF$
            unsafeTake(onTake) // retry
            // $COVERAGE-ON$
          }
        } else {
          val ((ax, notify), xs) = queue.dequeue
          val update = WaitForTake(ax, xs)
          if (stateRef.compareAndSet(current, update)) {
            // Signals completion of `take`
            onTake(Right(value))
            // Complete the `put` request waiting on a notification
            notify(rightUnit)
          } else {
            // $COVERAGE-OFF$
            unsafeTake(onTake) // retry
            // $COVERAGE-ON$
          }
        }

      case WaitForPut(reads, takes) =>
        if (!stateRef.compareAndSet(current, WaitForPut(reads, takes.enqueue(onTake)))) {
          // $COVERAGE-OFF$
          unsafeTake(onTake)
          // $COVERAGE-ON$
        }
    }
  }

  @tailrec
  private def unsafeRead(onRead: Listener[A]): Unit = {
    val current: State[A] = stateRef.get
    current match {
      case WaitForTake(value, _) =>
        // A value is available, so complete `read` immediately without
        // changing the sate
        onRead(Right(value))

      case WaitForPut(reads, takes) =>
        // No value available, enqueue the callback
        if (!stateRef.compareAndSet(current, WaitForPut(reads.enqueue(onRead), takes))) {
          // $COVERAGE-OFF$
          unsafeRead(onRead) // retry
          // $COVERAGE-ON$
        }
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

  /**
   * Internal API — Matches the callack type in `cats.effect.Async`,
   * but we don't care about about the error.
   */
  private type Listener[-A] = Either[Nothing, A] => Unit

  /** ADT modelling the internal state of `MVar`. */
  private sealed trait State[A]

  /** Private [[State]] builders.*/
  private object State {
    private[this] val ref = WaitForPut[Any](Queue.empty, Queue.empty)
    def apply[A](a: A): State[A] = WaitForTake(a, Queue.empty)
    /** `Empty` state, reusing the same instance. */
    def empty[A]: State[A] = ref.asInstanceOf[State[A]]
  }

  /**
   * `MVarAsync` state signaling it has `take` callbacks
   * registered and we are waiting for one or multiple
   * `put` operations.
   *
   * @param takes are the rest of the requests waiting in line,
   *        if more than one `take` requests were registered
   */
  private final case class WaitForPut[A](reads: Queue[Listener[A]], takes: Queue[Listener[A]])
    extends State[A]

  /**
   * `MVarAsync` state signaling it has one or more values enqueued,
   * to be signaled on the next `take`.
   *
   * @param value is the first value to signal
   * @param puts are the rest of the `put` requests, along with the
   *        callbacks that need to be called whenever the corresponding
   *        value is first in line (i.e. when the corresponding `put`
   *        is unblocked from the user's point of view)
   */
  private final case class WaitForTake[A](value: A, puts: Queue[(A, Listener[Unit])])
    extends State[A]
}

