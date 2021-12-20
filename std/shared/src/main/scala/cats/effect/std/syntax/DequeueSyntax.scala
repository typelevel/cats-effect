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

package cats.effect.std
package syntax

import cats.Monad
import cats.syntax.all._

trait DequeueSyntax {
  implicit def dequeueOps[F[_], A](wrapped: Dequeue[F, A]): DequeueOps[F, A] =
    new DequeueOps(wrapped)
}

final class DequeueOps[F[_], A] private[syntax] (private[syntax] val wrapped: Dequeue[F, A])
    extends AnyVal {
  private def assertMaxNPositive(maxN: Option[Int]): Unit = maxN match {
    case Some(n) if n <= 0 =>
      throw new IllegalArgumentException(s"Provided maxN parameter must be positive, was $n")
    case _ => ()
  }

  private def _tryTakeN(maxN: Option[Int])(
      tryTakeF: F[Option[A]])(implicit F: Monad[F]) = {
    assertMaxNPositive(maxN)
    F.tailRecM[(Option[List[A]], Int), Option[List[A]]](
      (None, 0)
    ) {
      case (list, i) =>
        if (maxN.contains(i)) list.map(_.reverse).asRight.pure[F]
        else {
          tryTakeF.map {
            case None => list.map(_.reverse).asRight
            case Some(x) =>
              if (list.isEmpty) (Some(List(x)), i + 1).asLeft
              else (list.map(x +: _), i + 1).asLeft
          }
        }
    }
  }

  /**
   * Attempts to dequeue elements from the front of the dequeue, if they available without
   * semantically blocking. This is a convenience method that recursively runs `tryTakeFront`. 
   * It does not provide any additional performance benefits.
   *
   * @param maxN
   *   The max elements to dequeue. Passing `None` will try to dequeue the whole queue.
   *
   * @return
   *   an effect that describes whether the dequeueing of an element from the dequeue succeeded
   *   without blocking, with `None` denoting that no element was available
   */
  def tryTakeFrontN(maxN: Option[Int])(implicit F: Monad[F]): F[Option[List[A]]] = 
    _tryTakeN(maxN)(wrapped.tryTakeFront)

  /**
   * Attempts to dequeue elements from the back of the dequeue, if they available without
   * semantically blocking. This is a convenience method that recursively runs `tryTakeFront`. 
   * It does not provide any additional performance benefits.
   *
   * @param maxN
   *   The max elements to dequeue. Passing `None` will try to dequeue the whole queue.
   *
   * @return
   *   an effect that describes whether the dequeueing of an element from the dequeue succeeded
   *   without blocking, with `None` denoting that no element was available
   */
  def tryTakeBackN(maxN: Option[Int])(implicit F: Monad[F]): F[Option[List[A]]] = 
    _tryTakeN(maxN)(wrapped.tryTakeBack)
}
