/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2013-2018 Paul Chiusano, and respective contributors 
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package cats
package effect
package concurrent

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.annotation.tailrec

/**
 * An asynchronous, concurrent mutable reference.
 *
 * Provides safe concurrent access and modification of its content, but no
 * functionality for synchronisation, which is instead handled by [[Promise]].
 * For this reason, a `Ref` is always initialised to a value.
 *
 * The implementation is nonblocking and lightweight, consisting essentially of
 * a purely functional wrapper over an `AtomicReference`.
 */
final class Ref[F[_], A] private (private val ar: AtomicReference[A]) {

  /**
   * Obtains the current value.
   *
   * Since `Ref` is always guaranteed to have a value, the returned action
   * completes immediately after being bound.
   */
  def get(implicit F: Sync[F]): F[A] = F.delay(ar.get)

  /**
   * Synchronously sets the current value to `a`.
   *
   * The returned action completes after the reference has been successfully set.
   *
   * Satisfies:
   *   `r.set(fa) *> r.get == fa`
   */
  def set(a: A)(implicit F: Sync[F]): F[Unit] = F.delay(ar.set(a))

  /**
   * Lazily sets the current value to the `a`.
   *
   * After the returned `F[Unit]` is bound, an update will eventually occur,
   * setting the current value to `a`.
   *
   * Satisfies:
   *   `r.lazySet(fa) == async.shiftStart(r.set(a))`
   * but it's significantly faster.
   */
  def lazySet(a: A)(implicit F: Sync[F]): F[Unit] = F.delay(ar.lazySet(a))

  /**
   * Obtains a snapshot of the current value, and a setter for updating it.
   * The setter may noop (in which case `false` is returned) if another concurrent
   * call to `access` uses its setter first.
   *
   * Once it has noop'd or been used once, a setter never succeeds again.
   *
   * Satisfies:
   *   `r.access.map(_._1) == r.get`
   *   `r.access.flatMap { case (v, setter) => setter(f(v)) } == r.tryModify(f).map(_.isDefined)`
   */
  def access(implicit F: Sync[F]): F[(A, A => F[Boolean])] = F.delay {
    val snapshot = ar.get
    val hasBeenCalled = new AtomicBoolean(false)
    def setter = (a: A) => F.delay(hasBeenCalled.compareAndSet(false, true) && ar.compareAndSet(snapshot, a))
    (snapshot, setter)
  }

  /**
   * Attempts to modify the current value once, returning `false` if another
   * concurrent modification completes between the time the variable is
   * read and the time it is set.
   */
  def tryModify(f: A => A)(implicit F: Sync[F]): F[Boolean] =
    F.map(tryModifyAndReturn(a => (f(a), ())))(_.isDefined)

  /**
   * Like `tryModify` but allows the update function to return an output value of
   * type `B`. The returned action completes with `None` if the value is not updated
   * successfully and `Some(b)` otherwise.
   */
  def tryModifyAndReturn[B](f: A => (A, B))(implicit F: Sync[F]): F[Option[B]] = F.delay {
    val c = ar.get
    val (u, b) = f(c)
    if (ar.compareAndSet(c, u)) Some(b)
    else None
  }


  /**
   * Modifies the current value using the supplied update function. If another modification
   * occurs between the time the current value is read and subsequently updated, the modification
   * is retried using the new value. Hence, `f` may be invoked multiple times.
   *
   * Satisfies:
   *   `r.modify(_ => a).void == r.set(a)`
   */
  def modify(f: A => A)(implicit F: Sync[F]): F[Unit] =
    modifyAndReturn(a => (f(a), ()))

  /**
   * Like `tryModifyAndReturn` but does not complete until the update has been successfully made.
   */
  def modifyAndReturn[B](f: A => (A, B))(implicit F: Sync[F]): F[B] = {
    @tailrec
    def spin: B = {
      val c = ar.get
      val (u, b) = f(c)
      if (!ar.compareAndSet(c, u)) spin
      else b
    }
    F.delay(spin)
  }
}

object Ref {

  /** Creates an asynchronous, concurrent mutable reference initialized to the supplied value. */
  def apply[F[_], A](a: A)(implicit F: Sync[F]): F[Ref[F, A]] =
    F.delay(unsafeCreate(a))

  /**
   * Like `apply` but returns the newly allocated ref directly instead of wrapping it in `F.delay`.
   * This method is considered unsafe because it is not referentially transparent -- it allocates
   * mutable state.
   */
  def unsafeCreate[F[_], A](a: A): Ref[F, A] =
    new Ref[F, A](new AtomicReference[A](a))
}

