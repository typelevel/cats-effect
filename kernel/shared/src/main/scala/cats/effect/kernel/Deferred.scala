/*
 * Copyright 2020-2024 Typelevel
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
package kernel

import cats.effect.kernel.Deferred.TransformedDeferred
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.LongMap

import java.util.concurrent.atomic.AtomicReference

/**
 * A purely functional synchronization primitive which represents a single value which may not
 * yet be available.
 *
 * When created, a `Deferred` is empty. It can then be completed exactly once, and never be made
 * empty again.
 *
 * `get` on an empty `Deferred` will block until the `Deferred` is completed. `get` on a
 * completed `Deferred` will always immediately return its content.
 *
 * `complete(a)` on an empty `Deferred` will set it to `a`, notify any and all readers currently
 * blocked on a call to `get`, and return true. `complete(a)` on a `Deferred` that has already
 * been completed will not modify its content, and return false.
 *
 * Albeit simple, `Deferred` can be used in conjunction with [[Ref]] to build complex concurrent
 * behaviour and data structures like queues and semaphores.
 *
 * Finally, the blocking mentioned above is semantic only, no actual threads are blocked by the
 * implementation.
 */
abstract class Deferred[F[_], A] extends DeferredSource[F, A] with DeferredSink[F, A] {

  /**
   * Modify the context `F` using transformation `f`.
   */
  def mapK[G[_]](f: F ~> G): Deferred[G, A] =
    new TransformedDeferred(this, f)
}

object Deferred {

  /**
   * Creates an unset Deferred. Every time you bind the resulting `F`, a new Deferred is
   * created. If you want to share one, pass it as an argument and `flatMap` once.
   */
  def apply[F[_], A](implicit F: GenConcurrent[F, _]): F[Deferred[F, A]] =
    F.deferred[A]

  /**
   * Like `apply` but returns the newly allocated Deferred directly instead of wrapping it in
   * `F.delay`. This method is considered unsafe because it is not referentially transparent --
   * it allocates mutable state. In general, you should prefer `apply` and use `flatMap` to get
   * state sharing.
   */
  def unsafe[F[_]: Async, A]: Deferred[F, A] = new AsyncDeferred[F, A]

  /**
   * Like [[apply]] but initializes state using another effect constructor
   */
  def in[F[_], G[_], A](implicit F: Sync[F], G: Async[G]): F[Deferred[G, A]] =
    F.delay(unsafe[G, A])

  sealed abstract private class State[A]
  private object State {
    final case class Set[A](a: A) extends State[A]
    final case class Unset[A](readers: LongMap[A => Unit], nextId: Long) extends State[A]

    val initialId = 1L
    val dummyId = 0L
  }

  final class AsyncDeferred[F[_], A](implicit F: Async[F]) extends Deferred[F, A] {
    // shared mutable state
    private[this] val ref = new AtomicReference[State[A]](
      State.Unset(LongMap.empty, State.initialId)
    )

    def get: F[A] = {
      // side-effectful
      def addReader(awakeReader: A => Unit): Long = {
        @tailrec
        def loop(): Long =
          ref.get match {
            case State.Set(a) =>
              awakeReader(a)
              State.dummyId // never used
            case s @ State.Unset(readers, nextId) =>
              val updated = State.Unset(
                readers + (nextId -> awakeReader),
                nextId + 1
              )

              if (!ref.compareAndSet(s, updated)) loop()
              else nextId
          }

        loop()
      }

      // side-effectful
      def deleteReader(id: Long): Unit = {
        @tailrec
        def loop(): Unit =
          ref.get match {
            case State.Set(_) => ()
            case s @ State.Unset(readers, _) =>
              val updated = s.copy(readers = readers - id)
              if (!ref.compareAndSet(s, updated)) loop()
              else ()
          }

        loop()
      }

      F.defer {
        ref.get match {
          case State.Set(a) =>
            F.pure(a)
          case State.Unset(_, _) =>
            F.async[A] { cb =>
              val resume = (a: A) => cb(Right(a))
              F.delay(addReader(awakeReader = resume)).map { id =>
                // if canceled
                F.delay(deleteReader(id)).some
              }
            }
        }
      }
    }

    def tryGet: F[Option[A]] =
      F.delay {
        ref.get match {
          case State.Set(a) => Some(a)
          case State.Unset(_, _) => None
        }
      }

    def complete(a: A): F[Boolean] = {
      def notifyReaders(readers: LongMap[A => Unit]): F[Unit] = {
        // LongMap iterators return values in unsigned key order,
        // which corresponds to the arrival order of readers since
        // insertion is governed by a monotonically increasing id
        val cursor = readers.valuesIterator
        var acc = F.unit

        while (cursor.hasNext) {
          val next = cursor.next()
          val task = F.delay(next(a))
          acc = acc >> task
        }

        acc
      }

      // side-effectful (even though it returns F[Unit])
      @tailrec
      def loop(): F[Boolean] =
        ref.get match {
          case State.Set(_) =>
            F.pure(false)
          case s @ State.Unset(readers, _) =>
            val updated = State.Set(a)
            if (!ref.compareAndSet(s, updated)) loop()
            else {
              val notify = if (readers.isEmpty) F.unit else notifyReaders(readers)
              notify.as(true)
            }
        }

      F.uncancelable(_ => F.defer(loop()))
    }
  }

  implicit def catsInvariantForDeferred[F[_]: Functor]: Invariant[Deferred[F, *]] =
    new Invariant[Deferred[F, *]] {
      override def imap[A, B](fa: Deferred[F, A])(f: A => B)(g: B => A): Deferred[F, B] =
        new Deferred[F, B] {
          override def get: F[B] =
            fa.get.map(f)
          override def complete(b: B): F[Boolean] =
            fa.complete(g(b))
          override def tryGet: F[Option[B]] =
            fa.tryGet.map(_.map(f))
        }
    }

  final private[kernel] class TransformedDeferred[F[_], G[_], A](
      underlying: Deferred[F, A],
      trans: F ~> G)
      extends Deferred[G, A] {
    override def get: G[A] = trans(underlying.get)
    override def tryGet: G[Option[A]] = trans(underlying.tryGet)
    override def complete(a: A): G[Boolean] = trans(underlying.complete(a))
  }
}

trait DeferredSource[F[_], A] extends Serializable {

  /**
   * Obtains the value of the `Deferred`, or waits until it has been completed. The returned
   * value may be canceled.
   */
  def get: F[A]

  /**
   * Obtains the current value of the `Deferred`, or None if it hasn't completed.
   */
  def tryGet: F[Option[A]]
}

object DeferredSource {
  implicit def catsFunctorForDeferredSource[F[_]: Functor]: Functor[DeferredSource[F, *]] =
    new Functor[DeferredSource[F, *]] {
      override def map[A, B](fa: DeferredSource[F, A])(f: A => B): DeferredSource[F, B] =
        new DeferredSource[F, B] {
          override def get: F[B] =
            fa.get.map(f)
          override def tryGet: F[Option[B]] =
            fa.tryGet.map(_.map(f))
        }
    }
}

trait DeferredSink[F[_], A] extends Serializable {

  /**
   * If this `Deferred` is empty, sets the current value to `a`, and notifies any and all
   * readers currently blocked on a `get`. Returns true.
   *
   * If this `Deferred` has already been completed, returns false.
   *
   * Satisfies: `Deferred[F, A].flatMap(r => r.complete(a) *> r.get) == a.pure[F]`
   */
  def complete(a: A): F[Boolean]
}

object DeferredSink {
  implicit def catsContravariantForDeferredSink[F[_]]: Contravariant[DeferredSink[F, *]] =
    new Contravariant[DeferredSink[F, *]] {
      override def contramap[A, B](fa: DeferredSink[F, A])(f: B => A): DeferredSink[F, B] =
        new DeferredSink[F, B] {
          override def complete(b: B): F[Boolean] =
            fa.complete(f(b))
        }
    }
}
