/*
 * Copyright 2020-2025 Typelevel
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

import cats.effect.kernel.{Fiber, MonadCancel, Outcome, Poll}

trait PollSyntax {

  implicit def pollOps[F[_]](
      wrapped: Poll[F]
  ): PollOps[F] =
    new PollOps(wrapped)
}

final class PollOps[F[_]] private[syntax] (private val wrapped: Poll[F]) extends AnyVal {

  def graceful[A](wait: F[A], cancel: F[Unit])(implicit F: MonadCancel[F, ?]): F[A] =
    F.pollGraceful(wrapped, wait, cancel)

  def fiberGraceful[E, A](fiber: Fiber[F, E, A])(
      implicit F: MonadCancel[F, E]): F[Outcome[F, E, A]] =
    F.pollFiberGraceful(wrapped, fiber)

}
