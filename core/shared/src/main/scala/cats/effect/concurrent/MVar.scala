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
package concurrent

import cats.effect.internals.{MVarAsync, MVarConcurrent}

/**
 * A mutable location, that is either empty or contains
 * a value of type `A`.
 *
 * It has 2 fundamental atomic operations:
 *
 *  - [[put]] which fills the var if empty, or blocks
 *    (asynchronously) until the var is empty again
 *  - [[take]] which empties the var if full, returning the contained
 *    value, or blocks (asynchronously) otherwise until there is
 *    a value to pull
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
abstract class MVar[F[_], A] {
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
   * Tries reading the current value, or blocks (asynchronously)
   * until there is a value available.
   *
   * This operation is atomic.
   *
   * @return a task that on evaluation will be completed after
   *         a value has been read
   */
  def read: F[A]
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
   *   MVar[IO].empty[Int]
   * }}}
   *
   * For creating an `MVar` with an initial value:
   * {{{
   *   MVar[IO].init("hello")
   * }}}
   *
   * @see [[async]]
   */
  def apply[F[_]](implicit F: Concurrent[F]): ConcurrentBuilder[F] =
    new ConcurrentBuilder[F](F)

  /**
   * Builds an [[MVar]] value for `F` data types that are [[Async]].
   *
   * Due to `Async`'s restrictions the yielded values by [[MVar.take]]
   * and [[MVar.put]] are not cancelable.
   *
   * This builder uses the
   * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type]]
   * technique.
   *
   * For creating an empty `MVar`:
   * {{{
   *   MVar.async[IO].empty[Int]
   * }}}
   *
   * For creating an `MVar` with an initial value:
   * {{{
   *   MVar.async[IO].init("hello")
   * }}}
   *
   * @see [[apply]]
   */
  def async[F[_]](implicit F: Async[F]): AsyncBuilder[F] =
    new AsyncBuilder[F](F)

  /**
   * Returned by the [[async]] builder.
   */
  final class AsyncBuilder[F[_]](val F: Async[F]) extends AnyVal {
    /** Builds an `MVar` with an initial value. */
    def init[A](a: A): F[MVar[F, A]] =
      F.delay(MVarAsync[F, A](a)(F))

    /** Builds an empty `MVar`. */
    def empty[A](implicit F: Async[F]): F[MVar[F, A]] =
      F.delay(MVarAsync.empty[F, A](F))
  }

  /**
   * Returned by the [[apply]] builder.
   */
  final class ConcurrentBuilder[F[_]](val F: Concurrent[F]) extends AnyVal {
    /** Builds an `MVar` with an initial value. */
    def init[A](a: A): F[MVar[F, A]] =
      F.delay(MVarConcurrent[F, A](a)(F))

    /** Builds an empty `MVar`. */
    def empty[A]: F[MVar[F, A]] =
      F.delay(MVarConcurrent.empty[F, A](F))
  }
}