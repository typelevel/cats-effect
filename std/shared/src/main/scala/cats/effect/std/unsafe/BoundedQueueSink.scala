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
package unsafe

trait BoundedQueueSink[F[_], A] extends QueueSink[F, A] {
  def unsafeTryOffer(a: A): Boolean
}

object BoundedQueueSink {
  implicit def catsContravariantForBoundedQueueSink[F[_]]
      : Contravariant[BoundedQueueSink[F, *]] =
    new Contravariant[BoundedQueueSink[F, *]] {
      override def contramap[A, B](fa: BoundedQueueSink[F, A])(
          f: B => A): BoundedQueueSink[F, B] =
        new BoundedQueueSink[F, B] {
          override def unsafeTryOffer(b: B): Boolean =
            fa.unsafeTryOffer(f(b))
          override def offer(b: B): F[Unit] =
            fa.offer(f(b))
          override def tryOffer(b: B): F[Boolean] =
            fa.tryOffer(f(b))
        }
    }
}
