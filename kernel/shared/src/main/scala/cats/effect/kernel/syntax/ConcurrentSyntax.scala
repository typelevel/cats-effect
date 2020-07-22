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

import cats.effect.kernel.{Concurrent, Fiber, Outcome}

trait ConcurrentSyntax {

  implicit def concurrentOps[F[_], A, E](
      wrapped: F[A]
  ): ConcurrentOps[F, A, E] =
    new ConcurrentOps(wrapped)
}

final class ConcurrentOps[F[_], A, E](val wrapped: F[A]) extends AnyVal {

  def start(implicit F: Concurrent[F, E]): F[Fiber[F, E, A]] = F.start(wrapped)

  def uncancelable(implicit F: Concurrent[F, E]): F[A] =
    F.uncancelable(_ => wrapped)

  def onCancel(fin: F[Unit])(implicit F: Concurrent[F, E]): F[A] =
    F.onCancel(wrapped, fin)

  def guarantee(fin: F[Unit])(implicit F: Concurrent[F, E]): F[A] =
    F.guarantee(wrapped, fin)

  def guaranteeCase(fin: Outcome[F, E, A] => F[Unit])(implicit F: Concurrent[F, E]): F[A] =
    F.guaranteeCase(wrapped)(fin)

  def bracket[B](use: A => F[B])(release: A => F[Unit])(implicit F: Concurrent[F, E]): F[B] =
    F.bracket(wrapped)(use)(release)

  def bracketCase[B](use: A => F[B])(release: (A, Outcome[F, E, B]) => F[Unit])(
      implicit F: Concurrent[F, E]): F[B] =
    F.bracketCase(wrapped)(use)(release)
}
