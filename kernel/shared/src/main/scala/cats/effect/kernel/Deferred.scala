/*
 * Copyright 2020-2021 Typelevel
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

import java.util.concurrent.atomic.AtomicReference

import cats.effect.kernel.Deferred.TransformedDeferred
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.LongMap

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
trait Deferred2[F[_], G[_], A] extends DeferredSource[F, A] with DeferredSink[G, A] {

  /**
   * Modify the contexts `F` and `G` using transformations `f` and `g`.
   */
  def mapK2[H[_], I[_]](f: F ~> H, g: G ~> I): Deferred2[H, I, A] =
    new TransformedDeferred[F, G, H, I, A](this, f, g)

  def tryGetG: G[Option[A]]
}

object Deferred2 {

  implicit final class DeferredSyntax[F[_], A](private val self: Deferred[F, A])
      extends AnyVal {

    /**
     * Modify the context `F` using transformation `f`.
     */
    def mapK[G[_]](f: F ~> G): Deferred[G, A] =
      self.mapK2(f, f)
  }

  /**
   * Creates an unset Deferred.
   * Every time you bind the resulting `F`, a new Deferred is created.
   * If you want to share one, pass it as an argument and `flatMap`
   * once.
   */
  def apply[F[_], A](implicit F: GenConcurrent[F, _]): F[Deferred[F, A]] =
    F.deferred[A]

  def apply[F[_], G[_], A](implicit F: Async[F], G: Sync[G]): G[Deferred2[F, G, A]] =
    in2[G, F, G, A]

  /**
   * Like `apply` but returns the newly allocated Deferred directly
   * instead of wrapping it in `F.delay`.  This method is considered
   * unsafe because it is not referentially transparent -- it
   * allocates mutable state.
   * In general, you should prefer `apply` and use `flatMap` to get state sharing.
   */
  def unsafe[F[_]: Async, A]: Deferred[F, A] = new AsyncDeferred[F, A]

  def unsafe2[F[_]: Async, G[_]: Sync, A]: Deferred2[F, G, A] = {
    new AsyncDeferred2[F, G, A] {
      final override def F = Async[F]
      final override def G = Sync[G]
    }
  }

  /**
   * Like [[apply]] but initializes state using another effect constructor
   */
  def in[F[_], G[_], A](implicit F: Sync[F], G: Async[G]): F[Deferred[G, A]] =
    F.delay(unsafe[G, A])

  def in2[F[_], G[_], H[_], A](
      implicit F: Sync[F],
      G: Async[G],
      H: Sync[H]): F[Deferred2[G, H, A]] = {
    F.delay(unsafe2[G, H, A])
  }

  sealed abstract private class State[A]
  private object State {
    final case class Set[A](a: A) extends State[A]
    final case class Unset[A](readers: LongMap[A => Unit], nextId: Long) extends State[A]

    val initialId = 1L
    val dummyId = 0L
  }

  final class AsyncDeferred[F[_], A](implicit FF: Async[F])
      extends AsyncDeferred2[F, F, A]
      with Deferred[F, A] {

    final override def F = FF
    final override def G = FF
  }

  trait AsyncDeferred2[F[_], G[_], A] extends Deferred2[F, G, A] {

    implicit protected def F: Async[F]
    implicit protected def G: Sync[G]

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
      F.delay { unsafeTryGet() }

    def tryGetG: G[Option[A]] =
      G.delay { unsafeTryGet() }

    private def unsafeTryGet(): Option[A] = {
      ref.get match {
        case State.Set(a) => Some(a)
        case State.Unset(_, _) => None
      }
    }

    def complete(a: A): G[Boolean] = {
      def notifyReaders(readers: LongMap[A => Unit]): G[Unit] = {
        // LongMap iterators return values in unsigned key order,
        // which corresponds to the arrival order of readers since
        // insertion is governed by a monotonically increasing id
        val cursor = readers.valuesIterator
        var acc = G.unit

        while (cursor.hasNext) {
          val next = cursor.next()
          val task = G.delay(next(a))
          acc = acc >> task
        }

        acc
      }

      // side-effectful (even though it returns G[Boolean])
      @tailrec
      def loop(): G[Boolean] =
        ref.get match {
          case State.Set(_) =>
            G.pure(false)
          case s @ State.Unset(readers, _) =>
            val updated = State.Set(a)
            if (!ref.compareAndSet(s, updated)) loop()
            else {
              val notify = if (readers.isEmpty) G.unit else notifyReaders(readers)
              notify.as(true)
            }
        }

      G.defer(loop())
    }
  }

  implicit def catsInvariantForDeferred[F[_]: Functor, G[_]: Functor]
      : Invariant[Deferred2[F, G, *]] =
    new Invariant[Deferred2[F, G, *]] {
      override def imap[A, B](fa: Deferred2[F, G, A])(f: A => B)(
          g: B => A): Deferred2[F, G, B] =
        new Deferred2[F, G, B] {
          override def get: F[B] =
            fa.get.map(f)
          override def complete(b: B): G[Boolean] =
            fa.complete(g(b))
          override def tryGet: F[Option[B]] =
            fa.tryGet.map(_.map(f))
          override def tryGetG: G[Option[B]] =
            fa.tryGetG.map(_.map(f))
        }
    }

  final private[kernel] class TransformedDeferred[F[_], G[_], H[_], I[_], A](
      underlying: Deferred2[F, G, A],
      trans1: F ~> H,
      trans2: G ~> I)
      extends Deferred2[H, I, A] {
    override def get: H[A] = trans1(underlying.get)
    override def tryGet: H[Option[A]] = trans1(underlying.tryGet)
    override def tryGetG: I[Option[A]] = trans2(underlying.tryGetG)
    override def complete(a: A): I[Boolean] = trans2(underlying.complete(a))
  }
}

trait DeferredSource[F[_], A] {

  /**
   * Obtains the value of the `Deferred`, or waits until it has been completed.
   * The returned value may be canceled.
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

trait DeferredSink[F[_], A] {

  /**
   * If this `Deferred` is empty, sets the current value to `a`, and notifies
   * any and all readers currently blocked on a `get`. Returns true.
   *
   * If this `Deferred` has already been completed, returns false.
   *
   * Satisfies:
   *   `Deferred[F, A].flatMap(r => r.complete(a) *> r.get) == a.pure[F]`
   */
  def complete(a: A): F[Boolean]
}

object DeferredSink {
  implicit def catsContravariantForDeferredSink[F[_]: Functor]
      : Contravariant[DeferredSink[F, *]] =
    new Contravariant[DeferredSink[F, *]] {
      override def contramap[A, B](fa: DeferredSink[F, A])(f: B => A): DeferredSink[F, B] =
        new DeferredSink[F, B] {
          override def complete(b: B): F[Boolean] =
            fa.complete(f(b))
        }
    }
}
