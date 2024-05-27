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

package cats.effect.kernel.syntax

import cats.effect.kernel.Resource

trait ResourceSyntax {
  implicit def effectResourceOps[F[_], A](wrapped: F[A]): EffectResourceOps[F, A] =
    new EffectResourceOps(wrapped)
}

final class EffectResourceOps[F[_], A] private[syntax] (private[syntax] val wrapped: F[A])
    extends AnyVal {
  def toResource: Resource[F, A] = Resource.eval(wrapped)
}
