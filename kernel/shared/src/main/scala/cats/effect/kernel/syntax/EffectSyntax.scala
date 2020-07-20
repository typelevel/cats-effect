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

import cats.effect.kernel.Effect

import scala.concurrent.ExecutionContext

trait EffectSyntax {
  implicit def effectOps[F[_], A](wrapped: F[A]): EffectOps[F, A] =
    new EffectOps(wrapped)
}

final class EffectOps[F[_], A](val wrapped: F[A]) extends AnyVal {
  def to[G[_]](implicit F: Effect[F], G: Effect[G]): G[A] =
    F.to[G](wrapped)
}
