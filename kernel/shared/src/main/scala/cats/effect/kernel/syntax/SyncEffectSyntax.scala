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

package cats.effect.syntax

import cats.effect.kernel.SyncEffect

trait SyncEffectSyntax {
  // `to` is also available on EffectSyntax, so we break the pattern to avoid ambiguity
  implicit def syncEffectOps[F[_], A](wrapped: F[A])(
      implicit F: SyncEffect[F]): SyncEffectOps[F, A] =
    new SyncEffectOps(wrapped)
}

final class SyncEffectOps[F[_], A](val wrapped: F[A])(implicit F: SyncEffect[F]) {
  def to[G[_]](implicit G: SyncEffect[G]): G[A] =
    F.to[G](wrapped)
}
