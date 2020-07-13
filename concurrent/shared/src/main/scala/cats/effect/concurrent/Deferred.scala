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

package cats
package effect
package concurrent

import cats.effect.concurrent.Deferred.TransformedDeferred
import cats.effect.kernel.{Async, Sync}
import cats.effect.internals.LinkedMap

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec

/**
 * A purely functional synchronization primitive which represents a single value
 * which may not yet be available.
 *
 * When created, a `Deferred` is empty. It can then be completed exactly once,
 * and never be made empty again.
 *
 * `get` on an empty `Deferred` will block until the `Deferred` is completed.
 * `get` on a completed `Deferred` will always immediately return its content.
 *
 * `complete(a)` on an empty `Deferred` will set it to `a`, and notify any and
 * all readers currently blocked on a call to `get`.
 * `complete(a)` on a `Deferred` that has already been completed will not modify
 * its content, and result in a failed `F`.
 *
 * Albeit simple, `Deferred` can be used in conjunction with [[Ref]] to build
 * complex concurrent behaviour and data structures like queues and semaphores.
 *
 * Finally, the blocking mentioned above is semantic only, no actual threads are
 * blocked by the implementation.
 */
abstract class Deferred[F[_], A] {

  /**
   * Obtains the value of the `Deferred`, or waits until it has been completed.
   * The returned value may be canceled.
   */
  def get: F[A]

  /**
   * If this `Deferred` is empty, sets the current value to `a`, and notifies
   * any and all readers currently blocked on a `get`.
   *
   * Note that the returned action may complete after the reference
   * has been successfully set: use `F.start(r.complete)` if you want
   * asynchronous behaviour.
   *
   * If this `Deferred` has already been completed, the returned
   * action immediately fails with an `IllegalStateException`. In the
   * uncommon scenario where this behavior is problematic, you can
   * handle failure explicitly using `attempt` or any other
   * `ApplicativeError`/`MonadError` combinator on the returned
   * action.
   *
   * Satisfies:
   *   `Deferred[F, A].flatMap(r => r.complete(a) *> r.get) == a.pure[F]`
   */
  def complete(a: A): F[Unit]

  /**
   * Modify the context `F` using transformation `f`.
   */
  def mapK[G[_]](f: F ~> G): Deferred[G, A] =
    new TransformedDeferred(this, f)
}

abstract class TryableDeferred[F[_], A] extends Deferred[F, A] {

  /**
   * Obtains the current value of the `Deferred`, or None if it hasn't completed.
   */
  def tryGet: F[Option[A]]
}

object Deferred {

  /** Creates an unset promise. **/
  def apply[F[_], A](implicit F: Async[F]): F[Deferred[F, A]] =
    F.delay(unsafe[F, A])

  /** Creates an unset tryable promise. **/
  def tryable[F[_], A](implicit F: Async[F]): F[TryableDeferred[F, A]] =
    F.delay(unsafeTryable[F, A])

  /**
   * Like `apply` but returns the newly allocated promise directly
   * instead of wrapping it in `F.delay`.  This method is considered
   * unsafe because it is not referentially transparent -- it
   * allocates mutable state.
   */
  def unsafe[F[_]: Async, A]: Deferred[F, A] = unsafeTryable[F, A]

  /** Like [[apply]] but initializes state using another effect constructor */
  def in[F[_], G[_], A](implicit F: Sync[F], G: Async[G]): F[Deferred[G, A]] =
    F.delay(unsafe[G, A])

  private def unsafeTryable[F[_]: Async, A]: TryableDeferred[F, A] =
    new AsyncDeferred[F, A](new AtomicReference(Deferred.State.Unset(LinkedMap.empty)))

  final private class Id

  sealed abstract private class State[A]
  private object State {
    final case class Set[A](a: A) extends State[A]
    final case class Unset[A](waiting: LinkedMap[Id, A => Unit]) extends State[A]
  }

  final private class AsyncDeferred[F[_], A](ref: AtomicReference[State[A]])(implicit F: Async[F])
      extends TryableDeferred[F, A] {
    def get: F[A] =
      F.defer {
        ref.get match {
          case State.Set(a) =>
            F.pure(a)
          case State.Unset(_) =>
            F.async[A] { cb =>
              val id = unsafeRegister(cb)
              @tailrec
              def unregister(): Unit =
                ref.get match {
                  case State.Set(_) => ()
                  case s @ State.Unset(waiting) =>
                    val updated = State.Unset(waiting - id)
                    if (ref.compareAndSet(s, updated)) ()
                    else unregister()
                }
              F.pure(Some(F.delay(unregister())))
            }
        }
      }

    def tryGet: F[Option[A]] =
      F.delay {
        ref.get match {
          case State.Set(a)   => Some(a)
          case State.Unset(_) => None
        }
      }

    private[this] def unsafeRegister(cb: Either[Throwable, A] => Unit): Id = {
      val id = new Id

      @tailrec
      def register(): Option[A] =
        ref.get match {
          case State.Set(a) => Some(a)
          case s @ State.Unset(waiting) =>
            val updated = State.Unset(waiting.updated(id, (a: A) => cb(Right(a))))
            if (ref.compareAndSet(s, updated)) None
            else register()
        }

      register().foreach(a => cb(Right(a)))
      id
    }

    def complete(a: A): F[Unit] =
      F.defer(unsafeComplete(a))

    @tailrec
    private def unsafeComplete(a: A): F[Unit] =
      ref.get match {
        case State.Set(_) =>
          throw new IllegalStateException("Attempting to complete a Deferred that has already been completed")

        case s @ State.Unset(_) =>
          if (ref.compareAndSet(s, State.Set(a))) {
            val list = s.waiting.values
            if (list.nonEmpty)
              notifyReadersLoop(a, list)
            else
              F.unit
          } else {
            unsafeComplete(a)
          }
      }

    private def notifyReadersLoop(a: A, r: Iterable[A => Unit]): F[Unit] = {
      var acc = F.unit
      val cursor = r.iterator
      while (cursor.hasNext) {
        val next = cursor.next()
        val task = F.map(F.start(F.delay(next(a))))(mapUnit)
        acc = F.flatMap(acc)(_ => task)
      }
      acc
    }

    private[this] val mapUnit = (_: Any) => ()
  }

  final private class TransformedDeferred[F[_], G[_], A](underlying: Deferred[F, A], trans: F ~> G)
      extends Deferred[G, A] {
    override def get: G[A] = trans(underlying.get)
    override def complete(a: A): G[Unit] = trans(underlying.complete(a))
  }

  val rightUnit = Right(())
}
