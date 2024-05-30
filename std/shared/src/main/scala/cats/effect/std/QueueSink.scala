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

trait QueueSink[F[_], A] {

  /**
   * Enqueues the given element at the back of the queue, possibly fiber blocking until
   * sufficient capacity becomes available.
   *
   * @param a
   *   the element to be put at the back of the queue
   */
  def offer(a: A): F[Unit]

  /**
   * Attempts to enqueue the given element at the back of the queue without semantically
   * blocking.
   *
   * @param a
   *   the element to be put at the back of the queue
   * @return
   *   an effect that describes whether the enqueuing of the given element succeeded without
   *   blocking
   */
  def tryOffer(a: A): F[Boolean]

  /**
   * Attempts to enqueue the given elements at the back of the queue without semantically
   * blocking. If an item in the list cannot be enqueued, the remaining elements will be
   * returned. This is a convenience method that recursively runs `tryOffer` and does not offer
   * any additional performance benefits.
   *
   * @param list
   *   the elements to be put at the back of the queue
   * @return
   *   an effect that contains the remaining valus that could not be offered.
   */
  def tryOfferN(list: List[A])(implicit F: Monad[F]): F[List[A]] =
    QueueSink.tryOfferN(list, tryOffer)
}

object QueueSink {

  private[std] def tryOfferN[F[_], A](list: List[A], tryOffer: A => F[Boolean])(
      implicit F: Monad[F]): F[List[A]] = list match {
    case Nil => F.pure(list)
    case h :: t =>
      tryOffer(h).ifM(
        tryOfferN(t, tryOffer),
        F.pure(list)
      )
  }

  implicit def catsContravariantForQueueSink[F[_]]: Contravariant[QueueSink[F, *]] =
    new Contravariant[QueueSink[F, *]] {
      override def contramap[A, B](fa: QueueSink[F, A])(f: B => A): QueueSink[F, B] =
        new QueueSink[F, B] {
          override def offer(b: B): F[Unit] =
            fa.offer(f(b))
          override def tryOffer(b: B): F[Boolean] =
            fa.tryOffer(f(b))
        }
    }
}
