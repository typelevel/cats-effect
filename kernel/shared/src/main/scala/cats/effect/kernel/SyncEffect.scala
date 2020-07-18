/*
 * Copyright 2020 Typelevel
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

package cats.effect.kernel

import cats.~>

trait SyncEffect[F[_]] extends Sync[F] {

  def to[G[_]]: PartiallyApplied[G] =
    new PartiallyApplied[G]

  def toK[G[_]: SyncEffect]: F ~> G

  final class PartiallyApplied[G[_]] {
    def apply[A](fa: F[A])(implicit G: SyncEffect[G]): G[A] =
      toK[G](G)(fa)
  }
}

object SyncEffect {
  def apply[F[_]](implicit F: SyncEffect[F]): F.type = F
}
