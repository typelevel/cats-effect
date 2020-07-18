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

trait Effect[F[_]] extends Async[F] {

  def to[G[_]]: PartiallyApplied[G] =
    new PartiallyApplied[G]

  def toK[G[_]: Effect]: F ~> G

  final class PartiallyApplied[G[_]] {
    def apply[A](fa: F[A])(implicit G: Effect[G]): G[A] =
      toK(G)(fa)
  }
}

object Effect {
  def apply[F[_]](implicit F: Effect[F]): F.type = F
}
