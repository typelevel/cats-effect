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

package cats.effect.kernel.syntax

import cats.effect.kernel.{Fiber, GenSpawn, Outcome, Resource}

trait GenSpawnSyntax {

  implicit def genSpawnOps[F[_], A, E](
      wrapped: F[A]
  ): GenSpawnOps[F, A, E] =
    new GenSpawnOps(wrapped)
}

final class GenSpawnOps[F[_], A, E](val wrapped: F[A]) extends AnyVal {

  def start(implicit F: GenSpawn[F, E]): F[Fiber[F, E, A]] = F.start(wrapped)

  def background(implicit F: GenSpawn[F, E]): Resource[F, F[Outcome[F, E, A]]] =
    F.background(wrapped)
}
