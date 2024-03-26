/*
 * Copyright 2020-2023 Typelevel
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

import cats.effect.kernel.{Concurrent, Poll, Resource}
import cats.syntax.functor._

abstract class Lock[F[_]] {
  def shared: Resource[F, Unit]
  def exclusive: Resource[F, Unit]
}

object Lock {
  def apply[F[_]: Concurrent]: F[Lock[F]] = apply(Long.MaxValue)

  def apply[F[_]: Concurrent](maxShared: Long): F[Lock[F]] =
    Semaphore[F](maxShared).map { semaphore =>
      new Lock[F] {
        override def shared: Resource[F, Unit] = semaphore.permit
        override def exclusive: Resource[F, Unit] =
          Resource.makeFull((poll: Poll[F]) => poll(semaphore.acquireN(maxShared)))(_ =>
            semaphore.releaseN(maxShared))
      }
    }
}
