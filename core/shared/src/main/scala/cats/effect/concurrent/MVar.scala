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
package concurrent

import cats.effect.concurrent.MVar.{TransformedMVar, TransformedMVar2}
import cats.effect.internals.{MVarAsync, MVarConcurrent}
import cats.~>
import com.github.ghik.silencer.silent

/**
 * @define mVarDescription A mutable location, that is either empty or contains a value of type `A`.
 *
 * It has the following fundamental atomic operations:
 *
 *  - [[put]] which fills the var if empty, or blocks
 *    (asynchronously) until the var is empty again
 *  - [[tryPut]] which fills the var if empty. returns true if successful
 *  - [[take]] which empties the var if full, returning the contained
 *    value, or blocks (asynchronously) otherwise until there is
 *    a value to pull
 *  - [[tryTake]] empties if full, returns None if empty.
 *  - [[read]] which reads the current value without touching it,
 *    assuming there is one, or otherwise it waits until a value
 *    is made available via `put`
 *  - `tryRead` returns a variable if it exists. Implemented in the successor [[MVar2]]
 *  - `swap` takes a value, replaces it and returns the taken value. Implemented in the successor [[MVar2]]
 *  - [[isEmpty]] returns true if currently empty
 *
 * The `MVar` is appropriate for building synchronization
 * primitives and performing simple inter-thread communications.
 * If it helps, it's similar with a `BlockingQueue(capacity = 1)`,
 * except that it doesn't block any threads, all waiting being
 * done asynchronously (via [[Async]] or [[Concurrent]] data types,
 * such as [[IO]]).
 *
 * Given its asynchronous, non-blocking nature, it can be used on
 * top of Javascript as well.
 *
 * Inspired by `Control.Concurrent.MVar` from Haskell and
 * by `scalaz.concurrent.MVar`.
 */
sealed private[concurrent] trait MVarDocumentation extends Any {}

/**
 * $mVarDescription
 */
@deprecated("`MVar` is now deprecated in favour of a new generation `MVar2` with `tryRead` and `swap` support", "2.2.0")
abstract class MVar[F[_], A] extends MVarDocumentation {

  /**
   * Returns `true` if the `MVar` is empty and can receive a `put`, or
   * `false` otherwise.
   *
   * Note that due to concurrent tasks, logic built in terms of `isEmpty`
   * is problematic.
   */
  def isEmpty: F[Boolean]

  /**
   * Fills the `MVar` if it is empty, or blocks (asynchronously)
   * if the `MVar` is full, until the given value is next in
   * line to be consumed on [[take]].
   *
   * This operation is atomic.
   *
   * @return a task that on evaluation will complete when the
   *         `put` operation succeeds in filling the `MVar`,
   *         with the given value being next in line to
   *         be consumed
   */
  def put(a: A): F[Unit]

  /**
   * Fill the `MVar` if we can do it without blocking,
   *
   * @return whether or not the put succeeded
   */
  def tryPut(a: A): F[Boolean]

  /**
   * Empties the `MVar` if full, returning the contained value,
   * or blocks (asynchronously) until a value is available.
   *
   * This operation is atomic.
   *
   * @return a task that on evaluation will be completed after
   *         a value was retrieved
   */
  def take: F[A]

  /**
   * empty the `MVar` if full
   *
   * @return an Option holding the current value, None means it was empty
   */
  def tryTake: F[Option[A]]

  /**
   * Tries reading the current value, or blocks (asynchronously)
   * until there is a value available.
   *
   * This operation is atomic.
   *
   * @return a task that on evaluation will be completed after
   *         a value has been read
   */
  def read: F[A]

  /**
   * Modify the context `F` using transformation `f`.
   */
  def mapK[G[_]](f: F ~> G): MVar[G, A] =
    new TransformedMVar(this, f)
}

/**
 * $mVarDescription
 *
 * The `MVar2` is the successor of `MVar` with [[tryRead]] and [[swap]]. It was implemented separately only to maintain
 * binary compatibility with `MVar`.
 */
@silent("deprecated")
abstract class MVar2[F[_], A] extends MVar[F, A] {

  /**
   * Replaces a value in MVar and returns the old value.

   * @param newValue is a new value
   * @return the value taken
   */
  def swap(newValue: A): F[A]

  /**
   * Returns the value without waiting or modifying.
   *
   * This operation is atomic.
   *
   * @return an Option holding the current value, None means it was empty
   */
  def tryRead: F[Option[A]]

  /**
   * Modify the context `F` using transformation `f`.
   */
  override def mapK[G[_]](f: F ~> G): MVar2[G, A] =
    new TransformedMVar2(this, f)
}

/** Builders for [[MVar]]. */
object MVar {

  /**
   * Builds an [[MVar]] value for `F` data types that are [[Concurrent]].
   *
   * Due to `Concurrent`'s capabilities, the yielded values by [[MVar.take]]
   * and [[MVar.put]] are cancelable.
   *
   * This builder uses the
   * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type]]
   * technique.
   *
   * For creating an empty `MVar`:
   * {{{
   *   MVar[IO].empty[Int] <-> MVar.empty[IO, Int]
   * }}}
   *
   * For creating an `MVar` with an initial value:
   * {{{
   *   MVar[IO].init("hello") <-> MVar.init[IO, String]("hello")
   * }}}
   *
   * @see [[of]]and [[empty]]
   */
  def apply[F[_]](implicit F: Concurrent[F]): ApplyBuilders[F] =
    new ApplyBuilders[F](F)

  /**
   * Creates a cancelable `MVar` that starts as empty.
   *
   * @see [[uncancelableEmpty]] for non-cancelable MVars
   *
   * @param F is a [[Concurrent]] constraint, needed in order to
   *        describe cancelable operations
   */
  def empty[F[_], A](implicit F: Concurrent[F]): F[MVar2[F, A]] =
    F.delay(MVarConcurrent.empty)

  /**
   * Creates a non-cancelable `MVar` that starts as empty.
   *
   * The resulting `MVar` has non-cancelable operations.
   *
   * WARN: some `Async` data types, like [[IO]], can be cancelable,
   * making `uncancelable` values unsafe. Such values are only useful
   * for optimization purposes, in cases where the use case does not
   * require cancellation or in cases in which an `F[_]` data type
   * that does not support cancellation is used.
   *
   * @see [[empty]] for creating cancelable MVars
   */
  def uncancelableEmpty[F[_], A](implicit F: Async[F]): F[MVar2[F, A]] =
    F.delay(MVarAsync.empty)

  /**
   * Creates a cancelable `MVar` that's initialized to an `initial`
   * value.
   *
   * @see [[uncancelableOf]] for non-cancelable MVars
   *
   * @param initial is a value that will be immediately available
   *        for the first `read` or `take` operation
   *
   * @param F is a [[Concurrent]] constraint, needed in order to
   *        describe cancelable operations
   */
  def of[F[_], A](initial: A)(implicit F: Concurrent[F]): F[MVar2[F, A]] =
    F.delay(MVarConcurrent(initial))

  /**
   * Creates a non-cancelable `MVar` that's initialized to an `initial`
   * value.
   *
   * The resulting `MVar` has non-cancelable operations.
   *
   * WARN: some `Async` data types, like [[IO]], can be cancelable,
   * making `uncancelable` values unsafe. Such values are only useful
   * for optimization purposes, in cases where the use case does not
   * require cancellation or in cases in which an `F[_]` data type
   * that does not support cancellation is used.
   *
   * @see [[of]] for creating cancelable MVars
   */
  def uncancelableOf[F[_], A](initial: A)(implicit F: Async[F]): F[MVar2[F, A]] =
    F.delay(MVarAsync(initial))

  /**
   * Like [[of]] but initializes state using another effect constructor
   */
  def in[F[_], G[_], A](initial: A)(implicit F: Sync[F], G: Concurrent[G]): F[MVar2[G, A]] =
    F.delay(MVarConcurrent(initial))

  /**
   * Like [[empty]] but initializes state using another effect constructor
   */
  def emptyIn[F[_], G[_], A](implicit F: Sync[F], G: Concurrent[G]): F[MVar2[G, A]] =
    F.delay(MVarConcurrent.empty)

  /**
   * Like [[uncancelableOf]] but initializes state using another effect constructor
   */
  def uncancelableIn[F[_], G[_], A](initial: A)(implicit F: Sync[F], G: Async[G]): F[MVar2[G, A]] =
    F.delay(MVarAsync(initial))

  /**
   * Like [[uncancelableEmpty]] but initializes state using another effect constructor
   */
  def uncancelableEmptyIn[F[_], G[_], A](implicit F: Sync[F], G: Async[G]): F[MVar2[G, A]] =
    F.delay(MVarAsync.empty)

  /**
   * Returned by the [[apply]] builder.
   */
  final class ApplyBuilders[F[_]](val F: Concurrent[F]) extends AnyVal {

    /**
     * Builds an `MVar` with an initial value.
     *
     * @see documentation for [[MVar.of]]
     */
    def of[A](a: A): F[MVar2[F, A]] =
      MVar.of(a)(F)

    /**
     * Builds an empty `MVar`.
     *
     * @see documentation for [[MVar.empty]]
     */
    def empty[A]: F[MVar2[F, A]] =
      MVar.empty(F)
  }

  final private[concurrent] class TransformedMVar[F[_], G[_], A](underlying: MVar[F, A], trans: F ~> G)
      extends MVar[G, A] {
    override def isEmpty: G[Boolean] = trans(underlying.isEmpty)
    override def put(a: A): G[Unit] = trans(underlying.put(a))
    override def tryPut(a: A): G[Boolean] = trans(underlying.tryPut(a))
    override def take: G[A] = trans(underlying.take)
    override def tryTake: G[Option[A]] = trans(underlying.tryTake)
    override def read: G[A] = trans(underlying.read)
  }

  final private[concurrent] class TransformedMVar2[F[_], G[_], A](underlying: MVar2[F, A], trans: F ~> G)
      extends MVar2[G, A] {
    override def isEmpty: G[Boolean] = trans(underlying.isEmpty)
    override def put(a: A): G[Unit] = trans(underlying.put(a))
    override def tryPut(a: A): G[Boolean] = trans(underlying.tryPut(a))
    override def take: G[A] = trans(underlying.take)
    override def tryTake: G[Option[A]] = trans(underlying.tryTake)
    override def read: G[A] = trans(underlying.read)
    override def tryRead: G[Option[A]] = trans(underlying.tryRead)
    override def swap(newValue: A): G[A] = trans(underlying.swap(newValue))
  }
}
