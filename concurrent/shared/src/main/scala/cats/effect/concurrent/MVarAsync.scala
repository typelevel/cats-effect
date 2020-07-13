/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

import cats.effect.concurrent.MVar2
import cats.effect.internals.Callback.rightUnit

import scala.annotation.tailrec
import scala.collection.immutable.Queue

/**
 * [[MVar]] implementation for [[Async]] data types.
 */
final private[effect] class MVarAsync[F[_], A] private (initial: MVarAsync.State[A])(implicit F: Async[F])
    extends MVar2[F, A] {
  import MVarAsync._

  /** Shared mutable state. */
  private[this] val stateRef = new AtomicReference[State[A]](initial)

  def put(a: A): F[Unit] =
    F.flatMap(tryPut(a)) {
      case true => F.unit
      case false =>
        F.asyncF(unsafePut(a))
    }

  def tryPut(a: A): F[Boolean] =
    F.suspend(unsafeTryPut(a))

  val tryTake: F[Option[A]] =
    F.suspend(unsafeTryTake())

  val take: F[A] =
    F.flatMap(tryTake) {
      case Some(a) => F.pure(a)
      case None    => F.asyncF(unsafeTake)
    }

  def read: F[A] =
    F.async(unsafeRead)

  def tryRead: F[Option[A]] = F.delay {
    stateRef.get match {
      case WaitForTake(value, _) => Some(value)
      case WaitForPut(_, _)      => None
    }
  }

  def swap(newValue: A): F[A] =
    F.flatMap(take) { oldValue =>
      F.map(put(newValue))(_ => oldValue)
    }

  def isEmpty: F[Boolean] =
    F.delay {
      stateRef.get match {
        case WaitForPut(_, _)  => true
        case WaitForTake(_, _) => false
      }
    }

  @tailrec
  private def unsafeTryPut(a: A): F[Boolean] =
    stateRef.get match {
      case WaitForTake(_, _) => F.pure(false)

      case current @ WaitForPut(reads, takes) =>
        var first: Listener[A] = null
        val update: State[A] =
          if (takes.isEmpty) State(a)
          else {
            val (x, rest) = takes.dequeue
            first = x
            if (rest.isEmpty) State.empty[A]
            else WaitForPut(Queue.empty, rest)
          }

        if (!stateRef.compareAndSet(current, update)) {
          unsafeTryPut(a) // retry
        } else if ((first ne null) || reads.nonEmpty) {
          streamPutAndReads(a, reads, first)
        } else {
          F.pure(true)
        }
    }

  @tailrec
  private def unsafePut(a: A)(onPut: Listener[Unit]): F[Unit] =
    stateRef.get match {
      case current @ WaitForTake(value, puts) =>
        val update = WaitForTake(value, puts.enqueue(a -> onPut))
        if (!stateRef.compareAndSet(current, update)) {
          unsafePut(a)(onPut) // retry
        } else {
          F.unit
        }

      case current @ WaitForPut(reads, takes) =>
        var first: Listener[A] = null
        val update: State[A] =
          if (takes.isEmpty) State(a)
          else {
            val (x, rest) = takes.dequeue
            first = x
            if (rest.isEmpty) State.empty[A]
            else WaitForPut(Queue.empty, rest)
          }

        if (!stateRef.compareAndSet(current, update)) {
          unsafePut(a)(onPut) // retry
        } else {
          F.map(streamPutAndReads(a, reads, first))(_ => onPut(rightUnit))
        }
    }

  @tailrec
  private def unsafeTryTake(): F[Option[A]] = {
    val current: State[A] = stateRef.get
    current match {
      case WaitForTake(value, queue) =>
        if (queue.isEmpty) {
          if (stateRef.compareAndSet(current, State.empty))
            F.pure(Some(value))
          else {
            unsafeTryTake() // retry
          }
        } else {
          val ((ax, awaitPut), xs) = queue.dequeue
          val update = WaitForTake(ax, xs)
          if (stateRef.compareAndSet(current, update)) {
            F.map(lightAsyncBoundary) { _ =>
              awaitPut(rightUnit)
              Some(value)
            }
          } else {
            unsafeTryTake() // retry
          }
        }
      case WaitForPut(_, _) =>
        pureNone
    }
  }

  @tailrec
  private def unsafeTake(onTake: Listener[A]): F[Unit] = {
    val current: State[A] = stateRef.get
    current match {
      case WaitForTake(value, queue) =>
        if (queue.isEmpty) {
          if (stateRef.compareAndSet(current, State.empty)) {
            // Signals completion of `take`
            onTake(Right(value))
            F.unit
          } else {
            unsafeTake(onTake) // retry
          }
        } else {
          val ((ax, awaitPut), xs) = queue.dequeue
          val update = WaitForTake(ax, xs)
          if (stateRef.compareAndSet(current, update)) {
            // Complete the `put` request waiting on a notification
            F.map(lightAsyncBoundary) { _ =>
              try awaitPut(rightUnit)
              finally onTake(Right(value))
            }
          } else {
            unsafeTake(onTake) // retry
          }
        }

      case WaitForPut(reads, takes) =>
        if (!stateRef.compareAndSet(current, WaitForPut(reads, takes.enqueue(onTake)))) {
          unsafeTake(onTake)
        } else {
          F.unit
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
          unsafeRead(onRead) // retry
        }
    }
  }

  private def streamPutAndReads(a: A, reads: Queue[Listener[A]], first: Listener[A]): F[Boolean] =
    F.map(lightAsyncBoundary) { _ =>
      val value = Right(a)
      // Satisfies all current `read` requests found
      streamAll(value, reads)
      // Satisfies the first `take` request found
      if (first ne null) first(value)
      // Signals completion of `put`
      true
    }

  private def streamAll(value: Either[Nothing, A], listeners: Iterable[Listener[A]]): Unit = {
    val cursor = listeners.iterator
    while (cursor.hasNext) cursor.next().apply(value)
  }

  private[this] val lightAsyncBoundary = {
    val k = (cb: Either[Throwable, Unit] => Unit) => cb(rightUnit)
    F.async[Unit](k)
  }

  private[this] val pureNone: F[Option[A]] =
    F.pure(None)
}

private[effect] object MVarAsync {

  /** Builds an [[MVarAsync]] instance with an `initial` value. */
  def apply[F[_], A](initial: A)(implicit F: Async[F]): MVar2[F, A] =
    new MVarAsync[F, A](State(initial))

  /** Returns an empty [[MVarAsync]] instance. */
  def empty[F[_], A](implicit F: Async[F]): MVar2[F, A] =
    new MVarAsync[F, A](State.empty)

  /**
   * Internal API — Matches the callback type in `cats.effect.Async`,
   * but we don't care about the error.
   */
  private type Listener[-A] = Either[Nothing, A] => Unit

  /** ADT modelling the internal state of `MVar`. */
  sealed private trait State[A]

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
  final private case class WaitForPut[A](reads: Queue[Listener[A]], takes: Queue[Listener[A]]) extends State[A]

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
  final private case class WaitForTake[A](value: A, puts: Queue[(A, Listener[Unit])]) extends State[A]
}
