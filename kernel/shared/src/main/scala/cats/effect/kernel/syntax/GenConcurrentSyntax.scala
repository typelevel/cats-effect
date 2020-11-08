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

import cats.effect.kernel.GenConcurrent

trait GenConcurrentSyntax {
  implicit def genConcurrentOps[F[_], E, A](wrapped: F[A]): GenConcurrentOps[F, E, A] =
    new GenConcurrentOps(wrapped)
}

final class GenConcurrentOps[F[_], E, A] private[syntax] (private[syntax] val wrapped: F[A])
    extends AnyVal {
  def memoize(implicit F: GenConcurrent[F, E]): F[F[A]] =
    F.memoize(wrapped)
}
