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
package std

import cats.effect.kernel.{Async, GenConcurrent, Sync}

/**
 * A purely functional, concurrent data structure which allows insertion and retrieval of
 * elements of type `A` in a last-in-first-out (LIFO) manner.
 *
 * The [[Stack#push]] operation never blocks and will always succeed.
 *
 * The [[Stack#pop]] operation semantically blocks when the `Stack` is empty, [[Stack#tryPop]]
 * allow for use cases which want to avoid blocking a fiber.
 *
 * The [[Stack#peek]] operation never blocks and will always succeed, it would however not
 * remove the element from the `Stack`, and there is no guarantee that a consequent `pop` would
 * return the same element.
 */
abstract class Stack[F[_], A] { self =>

  /**
   * Pushes the given element to the top of the `Stack`.
   *
   * @param a
   *   the element to push at the top of the `Stack`.
   */
  def push(a: A): F[Unit]

  /**
   * Pushes the given elements to the top of the `Stack`, the last element will be the final
   * top.
   *
   * @param as
   *   the elements to push at the top of the `Stack`.
   */
  def pushN(as: A*): F[Unit]

  /**
   * Takes the top element of `Stack`, if there is none it will semantically block until one is
   * made available. If multiple fibers are waiting for an element, they will be served in order
   * of arrival.
   */
  def pop: F[A]

  /**
   * Tries ta take the top element of `Stack`, if there is none it will return `None`.
   */
  def tryPop: F[Option[A]]

  /**
   * Returns the top element of the `Stack`, if there is any, without removing it.
   *
   * @note
   *   In a concurrent scenario, there is no guarantee that a `peek` followed by a `pop` or
   *   `tryPop` would return the same element.
   */
  def peek: F[Option[A]]

  /**
   * Returns the number of elements currently present in the `Stack`.
   *
   * @note
   *   In a concurrent scenario, this value must be considered stale immediately after returning
   *   it. There is no guarantee that doing a `pop` after seeing a value bigger than `0` will
   *   not block.
   */
  def size: F[Int]

  /**
   * Modifies the context in which this `Stack` is executed using the natural transformation
   * `f`.
   *
   * @return
   *   a `Stack` in the new context obtained by mapping the current one using `f`.
   */
  final def mapK[G[_]](f: F ~> G): Stack[G, A] =
    new Stack[G, A] {
      override def push(a: A): G[Unit] =
        f(self.push(a))

      override def pushN(as: A*): G[Unit] =
        f(self.pushN(as: _*))

      override def pop: G[A] =
        f(self.pop)

      override def tryPop: G[Option[A]] =
        f(self.tryPop)

      override def peek: G[Option[A]] =
        f(self.peek)

      override def size: G[Int] =
        f(self.size)
    }
}

object Stack {

  /**
   * Creates a new `Stack`.
   */
  def apply[F[_], A](implicit F: GenConcurrent[F, _]): F[Stack[F, A]] =
    ???

  /**
   * Creates a new `Stack`. Like `apply` but initializes state using another effect constructor.
   */
  def in[F[_], G[_], A](implicit F: Sync[F], G: Async[G]): F[Stack[G, A]] =
    ???
}
