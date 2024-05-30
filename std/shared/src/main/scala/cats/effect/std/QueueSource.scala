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

import cats.syntax.all._

trait QueueSource[F[_], A] {

  /**
   * Dequeues an element from the front of the queue, possibly fiber blocking until an element
   * becomes available.
   */
  def take: F[A]

  /**
   * Attempts to dequeue an element from the front of the queue, if one is available without
   * fiber blocking.
   *
   * @return
   *   an effect that describes whether the dequeueing of an element from the queue succeeded
   *   without blocking, with `None` denoting that no element was available
   */
  def tryTake: F[Option[A]]

  /**
   * Attempts to dequeue elements from the front of the queue, if they are available without
   * semantically blocking. This method does not guarantee any additional performance benefits
   * beyond simply recursively calling [[tryTake]], though some implementations will provide a
   * more efficient implementation.
   *
   * @param maxN
   *   The max elements to dequeue. Passing `None` will try to dequeue the whole queue.
   *
   * @return
   *   an effect that contains the dequeued elements
   */
  def tryTakeN(maxN: Option[Int])(implicit F: Monad[F]): F[List[A]] =
    QueueSource.tryTakeN(maxN, tryTake)

  def size: F[Int]
}

object QueueSource {

  private[std] def tryTakeN[F[_], A](maxN: Option[Int], tryTake: F[Option[A]])(
      implicit F: Monad[F]): F[List[A]] = {
    QueueSource.assertMaxNPositive(maxN)

    def loop(i: Int, limit: Int, acc: List[A]): F[List[A]] =
      if (i >= limit)
        F.pure(acc.reverse)
      else
        tryTake flatMap {
          case Some(a) => loop(i + 1, limit, a :: acc)
          case None => F.pure(acc.reverse)
        }

    maxN match {
      case Some(limit) => loop(0, limit, Nil)
      case None => loop(0, Int.MaxValue, Nil)
    }
  }

  private[std] def assertMaxNPositive(maxN: Option[Int]): Unit = maxN match {
    case Some(n) if n <= 0 =>
      throw new IllegalArgumentException(s"Provided maxN parameter must be positive, was $n")
    case _ => ()
  }

  implicit def catsFunctorForQueueSource[F[_]: Functor]: Functor[QueueSource[F, *]] =
    new Functor[QueueSource[F, *]] {
      override def map[A, B](fa: QueueSource[F, A])(f: A => B): QueueSource[F, B] =
        new QueueSource[F, B] {
          override def take: F[B] =
            fa.take.map(f)
          override def tryTake: F[Option[B]] = {
            fa.tryTake.map(_.map(f))
          }
          override def size: F[Int] =
            fa.size
        }
    }
}
