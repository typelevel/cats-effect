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
import cats.effect.internals.Callback.{rightUnit, Type => Listener}

import scala.annotation.tailrec

/**
 * [[MVar]] implementation for [[Concurrent]] data types.
 */
private[effect] final class MVarConcurrent[F[_], A] private (
  initial: MVarConcurrent.State[A])(implicit F: Concurrent[F])
  extends MVar[F, A] {

  import MVarConcurrent._

  private[this] val stateRef = new AtomicReference[State[A]](initial)

  def put(a: A): F[Unit] =
    F.cancelable(unsafePut(a))

  def take: F[A] =
    F.cancelable(unsafeTake)

  private def unregisterPut(id: Id): IO[Unit] = {
    @tailrec def loop(): Unit =
      stateRef.get() match {
        case current @ WaitForTake(_, listeners) =>
          val update = current.copy(listeners = listeners - id)
          if (!stateRef.compareAndSet(current, update)) loop()
        case _ =>
          ()
      }
    IO(loop())
  }

  private def unsafePut(a: A)(onPut: Listener[Unit]): IO[Unit] = {
    @tailrec def loop(): IO[Unit] = {
      val current: State[A] = stateRef.get

      current match {
        case _: Empty[_] =>
          if (stateRef.compareAndSet(current, WaitForTake(a, LinkedMap.empty))) {
            onPut(rightUnit)
            IO.unit
          } else {
            loop() // retry
          }
        case WaitForTake(value, listeners) =>
          val id = new Id
          val newMap = listeners.updated(id, (a, onPut))
          val update = WaitForTake(value, newMap)

          if (stateRef.compareAndSet(current, update))
            unregisterPut(id)
          else
            loop() // retry

        case current @ WaitForPut(listeners) =>
          if (listeners.isEmpty) {
            val update = WaitForTake(a, LinkedMap.empty)
            if (stateRef.compareAndSet(current, update)) {
              onPut(rightUnit)
              IO.unit
            } else {
              loop()
            }
          } else {
            val (cb, update) = listeners.dequeue
            if (stateRef.compareAndSet(current, WaitForPut(update))) {
              cb(Right(a))
              onPut(rightUnit)
              IO.unit
            } else {
              loop()
            }
          }
      }
    }

    if (a == null) {
      onPut(Left(new NullPointerException("null not supported in MVar")))
      IO.unit
    } else {
      loop()
    }
  }

  private def unregisterTake(id: Id): IO[Unit] = {
    @tailrec def loop(): Unit =
      stateRef.get() match {
        case current @ WaitForPut(listeners) =>
          val newMap = listeners - id
          val update: State[A] = if (newMap.isEmpty) State.empty else WaitForPut(newMap)
          if (!stateRef.compareAndSet(current, update)) loop()
        case _ => ()
      }
    IO(loop())
  }

  @tailrec
  private def unsafeTake(onTake: Listener[A]): IO[Unit] = {
    val current: State[A] = stateRef.get
    current match {
      case _: Empty[_] =>
        val id = new Id
        if (stateRef.compareAndSet(current, WaitForPut(LinkedMap.empty.updated(id, onTake))))
          unregisterTake(id)
        else
          unsafeTake(onTake) // retry

      case WaitForTake(value, queue) =>
        if (queue.isEmpty) {
          if (stateRef.compareAndSet(current, State.empty)) {
            onTake(Right(value))
            IO.unit
          } else {
            unsafeTake(onTake) // retry
          }
        } else {
          val ((ax, notify), xs) = queue.dequeue
          if (stateRef.compareAndSet(current, WaitForTake(ax, xs))) {
            onTake(Right(value))
            notify(rightUnit)
            IO.unit
          } else {
            unsafeTake(onTake) // retry
          }
        }

      case WaitForPut(queue) =>
        val id = new Id
        val newQueue = queue.updated(id, onTake)
        if (stateRef.compareAndSet(current, WaitForPut(newQueue)))
          unregisterTake(id)
        else
          unsafeTake(onTake)
    }
  }
}

private[effect] object MVarConcurrent {
  /** Builds an [[MVarConcurrent]] instance with an `initial` value. */
  def apply[F[_], A](initial: A)(implicit F: Concurrent[F]): MVar[F, A] =
    new MVarConcurrent[F, A](State(initial))

  /** Returns an empty [[MVarConcurrent]] instance. */
  def empty[F[_], A](implicit F: Concurrent[F]): MVar[F, A] =
    new MVarConcurrent[F, A](State.empty)

  private final class Id extends Serializable

  /** ADT modelling the internal state of `MVar`. */
  private sealed trait State[A]

  /** Private [[State]] builders.*/
  private object State {
    private[this] val ref = Empty()
    def apply[A](a: A): State[A] = WaitForTake(a, LinkedMap.empty)
    /** `Empty` state, reusing the same instance. */
    def empty[A]: State[A] = ref.asInstanceOf[State[A]]
  }

  /**
   * `MVarConcurrent` state signaling an empty location.
   *
   * Evolves into [[WaitForPut]] or [[WaitForTake]],
   * depending on which operation happens first.
   */
  private final case class Empty[A]() extends State[A]

  /**
   * `MVarConcurrent` state signaling it has `take` callbacks
   * registered and we are waiting for one or multiple
   * `put` operations.
   */
  private final case class WaitForPut[A](listeners: LinkedMap[Id, Listener[A]])
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
  private final case class WaitForTake[A](value: A, listeners: LinkedMap[Id, (A, Listener[Unit])])
    extends State[A]
}

