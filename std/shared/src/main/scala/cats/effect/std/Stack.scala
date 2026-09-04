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

import cats.effect.kernel._
import cats.syntax.all._

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
   * @param element
   *   the element to push at the top of the `Stack`.
   */
  def push(element: A): F[Unit]

  /**
   * Pushes the given elements to the top of the `Stack`, the last element will be the final
   * top.
   *
   * @param elements
   *   the elements to push at the top of the `Stack`.
   */
  def pushN(elements: A*): F[Unit]

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
      override def push(element: A): G[Unit] =
        f(self.push(element))

      override def pushN(elements: A*): G[Unit] =
        f(self.pushN(elements: _*))

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
  def apply[F[_], A](implicit F: Concurrent[F]): F[Stack[F, A]] =
    // Initialize the state with an empty stack.
    Ref.of[F, StackState[F, A]](StackState.empty).map(state => new ConcurrentImpl(state))

  /**
   * Creates a new `Stack`. Like `apply` but initializes state using another effect constructor.
   */
  def in[F[_], G[_], A](implicit F: Sync[F], G: Async[G]): F[Stack[G, A]] =
    // Initialize the state with an empty stack.
    Ref.in[F, G, StackState[G, A]](StackState.empty).map(state => new ConcurrentImpl(state))

  private final case class StackState[F[_], A](
      elements: List[A],
      waiters: collection.immutable.Queue[Deferred[F, A]]
  ) {
    type CopyResult = StackState[F, A]
    type ModifyResult[R] = (CopyResult, R)

    def push(element: A)(implicit F: Concurrent[F]): ModifyResult[F[Boolean]] =
      waiters.dequeueOption match {
        case Some((waiter, remainingWaiters)) =>
          this.copy(waiters = remainingWaiters) -> waiter.complete(element)

        case None =>
          this.copy(elements = element :: this.elements) -> F.pure(true)
      }

    def pushN(elements: Seq[A])(implicit F: Concurrent[F]): ModifyResult[F[Seq[A]]] =
      if (this.waiters.isEmpty)
        // If there are no waiters we just push all the elements in reverse order.
        this.copy(
          elements = this.elements.prependedAll(elements.reverseIterator)
        ) -> F.pure(Seq.empty)
      else {
        // Otherwise, if there is at least one waiter, we take all we can.
        val (remaining, waitersToNotify) =
          elements.reverse.align(this.waiters).partitionMap(_.unwrap)

        // We try to notify all the waiters we could take.
        val notifyWaiters = waitersToNotify.traverseFilter {
          case (element, waiter) =>
            waiter.complete(element).map {
              // If the waiter was successfully awaken, we remove the element from the Stack.
              case true => None
              // Otherwise, we preserve the element for retrying the push.
              case false => Some(element)
            }
        }

        // The remaining elements are either all elements, or all waiters.
        val newState = remaining.parTraverse(_.toEitherNec) match {
          case Left(remainingElements) =>
            // If only elements remained, then we preserve all the pending waiters,
            // and set the Stack elements as the remaining ones.
            // This is safe because the remaining elements are already in the correct order,
            // and since there was at least one waiter then we can assume there were not pending elements.
            this.copy(elements = remainingElements.toList)

          case Right(remainingWaiters) =>
            // If only waiters remained, then we create a new Queue from them.
            this.copy(waiters = collection.immutable.Queue.from(remainingWaiters))
        }

        newState -> notifyWaiters
      }

    def pop(waiter: Deferred[F, A]): ModifyResult[Option[A]] =
      elements match {
        case head :: tail =>
          this.copy(elements = tail) -> Some(head)

        case Nil =>
          this.copy(waiters = waiters.enqueue(waiter)) -> None
      }

    def removeWaiter(waiter: Deferred[F, A]): CopyResult =
      this.copy(waiters = this.waiters.filterNot(_ eq waiter))

    def tryPop: ModifyResult[Option[A]] =
      elements match {
        case head :: tail =>
          this.copy(elements = tail) -> Some(head)

        case Nil =>
          this -> None
      }
  }

  private object StackState {
    def empty[F[_], A]: StackState[F, A] = StackState(
      elements = List.empty,
      waiters = collection.immutable.Queue.empty
    )
  }

  private final class ConcurrentImpl[F[_], A](
      state: Ref[F, StackState[F, A]]
  )(
      implicit F: Concurrent[F]
  ) extends Stack[F, A] {
    override def push(element: A): F[Unit] =
      F.uncancelable { _ =>
        // Try to push an element to the Stack.
        state.flatModify(_.push(element)).flatMap {
          case true =>
            // If it worked we finish the process.
            F.unit

          case false =>
            // If it failed, we retry.
            this.push(element)
        }
      }

    override def pushN(elements: A*): F[Unit] =
      F.uncancelable { _ =>
        // If elements is empty, do nothing.
        if (elements.isEmpty) F.unit
        // Optimize for the singleton case.
        else if (elements.sizeIs == 1) this.push(elements.head)
        else
          // Otherwise try to push all the elements at once.
          state.flatModify(_.pushN(elements)).flatMap { failedElements =>
            // For the elements we failed to push, we retry.
            this.pushN(failedElements.reverse: _*)
          }
      }

    override final val pop: F[A] =
      F.uncancelable { poll =>
        Deferred[F, A].flatMap { waiter =>
          // Try to pop the head of the Stack.
          state.modify(_.pop(waiter)).flatMap {
            case Some(head) =>
              // If there is one, we simply return it.
              F.pure(head)

            case None =>
              // If there wasn't one,
              // we already added our waiter at the end of the waiters queue.
              // We then need to wait for it to be completed.
              // However, we may be cancelled while waiting for that.
              // If we are cancelled, then we will try to invalidate our waiter:
              val waitCancelledFinalizer = waiter.complete(null.asInstanceOf[A]).flatMap {
                case true =>
                  // If we managed to invalidate our waiter,
                  // we try to remove it from the waiters queue.
                  state.update(_.removeWaiter(waiter)).void

                case false =>
                  // But, if we didn't managed to invalidate it.
                  // Then, that means we managed to receive a pushed element.
                  // Thus, we have to push it again to avoid it getting lost.
                  waiter.get.flatMap(element => this.push(element))
              }

              F.onCancel(poll(waiter.get), waitCancelledFinalizer)
          }
        }
      }

    override final val tryPop: F[Option[A]] =
      F.uncancelable(_ => state.modify(_.tryPop))

    override final val peek: F[Option[A]] =
      state.get.map(_.elements.headOption)

    override final val size: F[Int] =
      state.get.map(_.elements.size)
  }
}
