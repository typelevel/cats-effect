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

trait UnboundedQueueSink[F[_], A] extends QueueSink[F, A] with BoundedQueueSink[F, A] {
  def unsafeOffer(a: A): Unit

  def unsafeTryOffer(a: A): Boolean = {
    unsafeOffer(a)
    true
  }
}

object UnboundedQueueSink {
  implicit def catsContravariantForUnboundedQueueSink[F[_]]
      : Contravariant[UnboundedQueueSink[F, *]] =
    new Contravariant[UnboundedQueueSink[F, *]] {
      override def contramap[A, B](fa: UnboundedQueueSink[F, A])(
          f: B => A): UnboundedQueueSink[F, B] =
        new UnboundedQueueSink[F, B] {
          override def unsafeOffer(b: B): Unit =
            fa.unsafeOffer(f(b))
          override def offer(b: B): F[Unit] =
            fa.offer(f(b))
          override def tryOffer(b: B): F[Boolean] =
            fa.tryOffer(f(b))
        }
    }
}
