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

package cats
package effect
package concurrent

import cats.data.State

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.annotation.tailrec

/**
 * An asynchronous, concurrent mutable reference.
 *
 * Provides safe concurrent access and modification of its content, but no
 * functionality for synchronisation, which is instead handled by [[Deferred]].
 * For this reason, a `Ref` is always initialised to a value.
 *
 * The default implementation is nonblocking and lightweight, consisting essentially
 * of a purely functional wrapper over an `AtomicReference`.
 */
abstract class Ref[F[_], A] {

  /**
   * Obtains the current value.
   *
   * Since `Ref` is always guaranteed to have a value, the returned action
   * completes immediately after being bound.
   */
  def get: F[A]

  /**
   * Sets the current value to `a`.
   *
   * The returned action completes after the reference has been successfully set.
   *
   * Satisfies:
   *   `r.set(fa) *> r.get == fa`
   */
  def set(a: A): F[Unit]

  /**
   * Eventually sets the current value to the `a`.
   *
   * The returned action completes after the reference has been lazily set.
   */
  def lazySet(a: A): F[Unit]

  /**
   * Replaces the current value with `a`, returning the previous value.
   */
  def getAndSet(a: A): F[A]

  /**
   * Sets the value to `newValue` if the current value is `expected`.
   * Note: reference equality is used here.
   */
  def compareAndSet(expected: A, newValue: A): F[Boolean]

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
  def access: F[(A, A => F[Boolean])]

  /**
   * Attempts to modify the current value once, returning `false` if another
   * concurrent modification completes between the time the variable is
   * read and the time it is set.
   */
  def tryModify(f: A => A): F[Boolean]

  /**
   * Like `tryModify` but allows the update function to return an output value of
   * type `B`. The returned action completes with `None` if the value is not updated
   * successfully and `Some(b)` otherwise.
   */
  def tryModifyAndReturn[B](f: A => (A, B)): F[Option[B]]

  /**
   * Modifies the current value using the supplied update function. If another modification
   * occurs between the time the current value is read and subsequently updated, the modification
   * is retried using the new value. Hence, `f` may be invoked multiple times.
   *
   * Satisfies:
   *   `r.modify(_ => a) == r.set(a)`
   */
  def modify(f: A => A): F[Unit]

  /**
   * Like `tryModifyAndReturn` but does not complete until the update has been successfully made.
   */
  def modifyAndReturn[B](f: A => (A, B)): F[B]

  /**
   * Update the value of this ref with a state computation.
   *
   * The current value of this ref is used as the initial state and the computed output state
   * is stored in this ref after computation completes. If a concurrent modification occurs,
   * `None` is returned.
   */
  def tryModifyState[B](state: State[A, B]): F[Option[B]]

  /**
   * Like [[tryModifyState]] but retries the modification until successful.
   */
  def modifyState[B](state: State[A, B]): F[B]
}

object Ref {

  /** Creates an asynchronous, concurrent mutable reference initialized to the supplied value. */
  def apply[F[_], A](a: A)(implicit F: Sync[F]): F[Ref[F, A]] =
    F.delay(unsafe(a))

  /**
   * Like `apply` but returns the newly allocated ref directly instead of wrapping it in `F.delay`.
   * This method is considered unsafe because it is not referentially transparent -- it allocates
   * mutable state.
   */
  def unsafe[F[_]: Sync, A](a: A): Ref[F, A] =
    new SyncRef[F, A](new AtomicReference[A](a))

  private final class SyncRef[F[_], A](ar: AtomicReference[A])(implicit F: Sync[F]) extends Ref[F, A] {

    def get: F[A] = F.delay(ar.get)

    def set(a: A): F[Unit] = F.delay(ar.set(a))

    def lazySet(a: A): F[Unit] = F.delay(ar.lazySet(a))

    def getAndSet(a: A): F[A] = F.delay(ar.getAndSet(a))
    
    def compareAndSet(expected: A, newValue: A): F[Boolean] = F.delay(ar.compareAndSet(expected, newValue))

    def access: F[(A, A => F[Boolean])] = F.delay {
      val snapshot = ar.get
      val hasBeenCalled = new AtomicBoolean(false)
      def setter = (a: A) => F.delay(hasBeenCalled.compareAndSet(false, true) && ar.compareAndSet(snapshot, a))
      (snapshot, setter)
    }

    def tryModify(f: A => A): F[Boolean] =
      F.map(tryModifyAndReturn(a => (f(a), ())))(_.isDefined)

    def tryModifyAndReturn[B](f: A => (A, B)): F[Option[B]] = F.delay {
      val c = ar.get
      val (u, b) = f(c)
      if (ar.compareAndSet(c, u)) Some(b)
      else None
    }

    def modify(f: A => A): F[Unit] =
      modifyAndReturn(a => (f(a), ()))

    def modifyAndReturn[B](f: A => (A, B)): F[B] = {
      @tailrec
      def spin: B = {
        val c = ar.get
        val (u, b) = f(c)
        if (!ar.compareAndSet(c, u)) spin
        else b
      }
      F.delay(spin)
    }

    def tryModifyState[B](state: State[A, B]): F[Option[B]] = {
      val f = state.runF.value
      tryModifyAndReturn(a => f(a).value)
    }

    def modifyState[B](state: State[A, B]): F[B] = {
      val f = state.runF.value
      modifyAndReturn(a => f(a).value)
    }
  }
}

