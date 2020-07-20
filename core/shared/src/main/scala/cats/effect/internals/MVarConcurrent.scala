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

import cats.effect.concurrent.{MVar2, Ref}
import cats.effect.internals.Callback.rightUnit

import scala.annotation.tailrec

/**
 * [[MVar]] implementation for [[Concurrent]] data types.
 */
final private[effect] class MVarConcurrent[F[_], A] private (initial: MVarConcurrent.State[A])(
  implicit F: Concurrent[F]
) extends MVar2[F, A] {
  import MVarConcurrent._

  /** Shared mutable state. */
  private[this] val stateRef = new AtomicReference[State[A]](initial)

  def put(a: A): F[Unit] =
    F.flatMap(tryPut(a)) {
      case true =>
        F.unit // happy path
      case false =>
        Concurrent.cancelableF(unsafePut(a))
    }

  def tryPut(a: A): F[Boolean] =
    F.suspend(unsafeTryPut(a))

  val tryTake: F[Option[A]] =
    F.suspend(unsafeTryTake())

  val take: F[A] =
    F.flatMap(tryTake) {
      case Some(a) =>
        F.pure(a) // happy path
      case None =>
        Concurrent.cancelableF(unsafeTake)
    }

  val read: F[A] =
    F.cancelable(unsafeRead)

  def tryRead =
    F.delay {
      stateRef.get match {
        case WaitForTake(value, _, _) => Some(value)
        case WaitForPut(_, _, _)      => None
      }
    }

  def isEmpty: F[Boolean] =
    F.delay {
      stateRef.get match {
        case WaitForPut(_, _, _)  => true
        case WaitForTake(_, _, _) => false
      }
    }

  def swap(newValue: A): F[A] =
    F.continual(take) {
      case Left(t)         => F.raiseError(t)
      case Right(oldValue) => F.as(put(newValue), oldValue)
    }

  def use[B](f: A => F[B]): F[B] =
    modify(a => F.map(f(a))((a, _)))

  def modify[B](f: A => F[(A, B)]): F[B] =
    F.bracket(Ref[F].of[Option[A]](None)) { signal =>
      F.flatMap(F.continual[A, A](take) {
        case Left(t)  => F.raiseError(t)
        case Right(a) => F.as(signal.set(Some(a)), a)
      }) { a =>
        F.continual[(A, B), B](f(a)) {
          case Left(t)          => F.raiseError(t)
          case Right((newA, b)) => F.as(signal.set(Some(newA)), b)
        }
      }
    } { signal =>
      F.flatMap(signal.get) {
        case Some(a) => put(a)
        case None    => F.unit
      }
    }

  def modify_(f: A => F[A]): F[Unit] =
    modify(a => F.map(f(a))((_, ())))

  @tailrec private def unsafeTryPut(a: A): F[Boolean] =
    stateRef.get match {
      case WaitForTake(_, _, _) => F.pure(false)

      case current @ WaitForPut(reads, takes, nextId) =>
        var first: Listener[A] = null
        val update: State[A] =
          if (takes.isEmpty) State(a)
          else {
            val (x, rest) = takes.dequeue
            first = x
            if (rest.isEmpty) State.empty[A]
            else WaitForPut(LinkedLongMap.empty, rest, nextId)
          }

        if (!stateRef.compareAndSet(current, update)) {
          unsafeTryPut(a) // retry
        } else if ((first ne null) || !reads.isEmpty) {
          streamPutAndReads(a, first, reads)
        } else {
          trueF
        }
    }

  @tailrec private def unsafePut(a: A)(onPut: Listener[Unit]): F[CancelToken[F]] =
    stateRef.get match {
      case current @ WaitForTake(value, listeners, nextId) =>
        val newMap = listeners.updated(nextId, (a, onPut))
        val update = WaitForTake(value, newMap, nextId + 1)

        if (stateRef.compareAndSet(current, update)) {
          F.pure(F.delay(unsafeCancelPut(nextId)))
        } else {
          unsafePut(a)(onPut) // retry
        }

      case current @ WaitForPut(reads, takes, nextId) =>
        var first: Listener[A] = null
        val update: State[A] =
          if (takes.isEmpty) State(a)
          else {
            val (x, rest) = takes.dequeue
            first = x
            if (rest.isEmpty) State.empty[A]
            else WaitForPut(LinkedLongMap.empty, rest, nextId)
          }

        if (stateRef.compareAndSet(current, update)) {
          if ((first ne null) || !reads.isEmpty)
            F.map(streamPutAndReads(a, first, reads)) { _ =>
              onPut(rightUnit)
              F.unit
            }
          else {
            onPut(rightUnit)
            pureToken
          }
        } else {
          unsafePut(a)(onPut) // retry
        }
    }

  // Impure function meant to cancel the put request
  @tailrec private def unsafeCancelPut(id: Long): Unit =
    stateRef.get() match {
      case current @ WaitForTake(_, listeners, _) =>
        val update = current.copy(listeners = listeners - id)
        if (!stateRef.compareAndSet(current, update)) {
          unsafeCancelPut(id) // retry
        }
      case _ =>
        ()
    }

  @tailrec
  private def unsafeTryTake(): F[Option[A]] = {
    val current: State[A] = stateRef.get
    current match {
      case WaitForTake(value, queue, nextId) =>
        if (queue.isEmpty) {
          if (stateRef.compareAndSet(current, State.empty))
            F.pure(Some(value))
          else {
            unsafeTryTake() // retry
          }
        } else {
          val ((ax, notify), xs) = queue.dequeue
          val update = WaitForTake(ax, xs, nextId)
          if (stateRef.compareAndSet(current, update)) {
            // Complete the `put` request waiting on a notification
            F.map(F.start(F.delay(notify(rightUnit))))(_ => Some(value))
          } else {
            unsafeTryTake() // retry
          }
        }

      case WaitForPut(_, _, _) =>
        F.pure(None)
    }
  }

  @tailrec
  private def unsafeTake(onTake: Listener[A]): F[CancelToken[F]] =
    stateRef.get match {
      case current @ WaitForTake(value, queue, nextId) =>
        if (queue.isEmpty) {
          if (stateRef.compareAndSet(current, State.empty)) {
            onTake(Right(value))
            pureToken
          } else {
            unsafeTake(onTake) // retry
          }
        } else {
          val ((ax, notify), xs) = queue.dequeue
          if (stateRef.compareAndSet(current, WaitForTake(ax, xs, nextId))) {
            F.map(F.start(F.delay(notify(rightUnit)))) { _ =>
              onTake(Right(value))
              F.unit
            }
          } else {
            unsafeTake(onTake) // retry
          }
        }

      case current @ WaitForPut(reads, takes, nextId) =>
        val newQueue = takes.updated(nextId, onTake)
        if (stateRef.compareAndSet(current, WaitForPut(reads, newQueue, nextId + 1)))
          F.pure(F.delay(unsafeCancelTake(nextId)))
        else {
          unsafeTake(onTake) // retry
        }
    }

  @tailrec private def unsafeCancelTake(id: Long): Unit =
    stateRef.get() match {
      case current @ WaitForPut(reads, takes, nextId) =>
        val newMap = takes - id
        val update: State[A] = WaitForPut(reads, newMap, nextId)
        if (!stateRef.compareAndSet(current, update)) {
          unsafeCancelTake(id)
        }
      case _ =>
    }
  @tailrec
  private def unsafeRead(onRead: Listener[A]): F[Unit] = {
    val current: State[A] = stateRef.get
    current match {
      case WaitForTake(value, _, _) =>
        // A value is available, so complete `read` immediately without
        // changing the sate
        onRead(Right(value))
        F.unit

      case WaitForPut(reads, takes, nextId) =>
        // No value available, enqueue the callback
        val newQueue = reads.updated(nextId, onRead)
        if (stateRef.compareAndSet(current, WaitForPut(newQueue, takes, nextId + 1)))
          F.delay(unsafeCancelRead(nextId))
        else {
          unsafeRead(onRead) // retry
        }
    }
  }

  private def unsafeCancelRead(id: Long): Unit =
    stateRef.get() match {
      case current @ WaitForPut(reads, takes, nextId) =>
        val newMap = reads - id
        val update: State[A] = WaitForPut(newMap, takes, nextId)
        if (!stateRef.compareAndSet(current, update)) {
          unsafeCancelRead(id)
        }
      case _ => ()
    }

  private def streamPutAndReads(a: A, put: Listener[A], reads: LinkedLongMap[Listener[A]]): F[Boolean] = {
    val value = Right(a)
    // Satisfies all current `read` requests found
    val task = streamAll(value, reads.values)
    // Continue with signaling the put
    F.flatMap(task) { _ =>
      // Satisfies the first `take` request found
      if (put ne null)
        F.map(F.start(F.delay(put(value))))(mapTrue)
      else
        trueF
    }
  }

  // For streaming a value to a whole `reads` collection
  private def streamAll(value: Either[Nothing, A], listeners: Iterable[Listener[A]]): F[Unit] = {
    var acc: F[Fiber[F, Unit]] = null.asInstanceOf[F[Fiber[F, Unit]]]
    val cursor = listeners.iterator
    while (cursor.hasNext) {
      val next = cursor.next()
      val task = F.start(F.delay(next(value)))
      acc = if (acc == null) task else F.flatMap(acc)(_ => task)
    }
    if (acc == null) F.unit
    else F.map(acc)(mapUnit)
  }

  private[this] val mapUnit = (_: Any) => ()
  private[this] val mapTrue = (_: Any) => true
  private[this] val trueF = F.pure(true)
  private[this] val pureToken = F.pure(F.unit)
}

private[effect] object MVarConcurrent {

  /** Builds an [[MVarConcurrent]] instance with an `initial` value. */
  def apply[F[_], A](initial: A)(implicit F: Concurrent[F]): MVar2[F, A] =
    new MVarConcurrent[F, A](State(initial))

  /** Returns an empty [[MVarConcurrent]] instance. */
  def empty[F[_], A](implicit F: Concurrent[F]): MVar2[F, A] =
    new MVarConcurrent[F, A](State.empty)

  /**
   * Internal API — Matches the callback type in `cats.effect.Async`,
   * but we don't care about the error.
   */
  private type Listener[-A] = Either[Nothing, A] => Unit

  /** ADT modelling the internal state of `MVar`. */
  sealed private trait State[A]

  /** Private [[State]] builders.*/
  private object State {
    private[this] val ref = WaitForPut[Any](LinkedLongMap.empty, LinkedLongMap.empty, 0)
    def apply[A](a: A): State[A] = WaitForTake(a, LinkedLongMap.empty, 0)

    /** `Empty` state, reusing the same instance. */
    def empty[A]: State[A] = ref.asInstanceOf[State[A]]
  }

  /**
   * `MVarConcurrent` state signaling it has `take` callbacks
   * registered and we are waiting for one or multiple
   * `put` operations.
   */
  final private case class WaitForPut[A](reads: LinkedLongMap[Listener[A]],
                                         takes: LinkedLongMap[Listener[A]],
                                         nextId: Long)
      extends State[A]

  /**
   * `MVarConcurrent` state signaling it has one or more values enqueued,
   * to be signaled on the next `take`.
   *
   * @param value is the first value to signal
   * @param listeners are the rest of the `put` requests, along with the
   *        callbacks that need to be called whenever the corresponding
   *        value is first in line (i.e. when the corresponding `put`
   *        is unblocked from the user's point of view)
   */
  final private case class WaitForTake[A](value: A, listeners: LinkedLongMap[(A, Listener[Unit])], nextId: Long)
      extends State[A]
}
