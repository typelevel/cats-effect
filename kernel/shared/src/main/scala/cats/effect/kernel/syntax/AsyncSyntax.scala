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

import cats.effect.kernel._

import scala.concurrent.ExecutionContext

import java.util.concurrent.Executor

trait AsyncSyntax {
  implicit def asyncOps[F[_], A](wrapped: F[A]): AsyncOps[F, A] =
    new AsyncOps(wrapped)
}

final class AsyncOps[F[_], A] private[syntax] (private[syntax] val wrapped: F[A])
    extends AnyVal {
  def evalOn(ec: ExecutionContext)(implicit F: Async[F]): F[A] =
    Async[F].evalOn(wrapped, ec)

  def evalOnExecutor(executor: Executor)(implicit F: Async[F]): F[A] =
    Async[F].evalOnExecutor(wrapped, executor)

  def startOn(ec: ExecutionContext)(implicit F: Async[F]): F[Fiber[F, Throwable, A]] =
    Async[F].startOn(wrapped, ec)

  def startOnExecutor(executor: Executor)(implicit F: Async[F]): F[Fiber[F, Throwable, A]] =
    Async[F].startOnExecutor(wrapped, executor)

  def backgroundOn(ec: ExecutionContext)(
      implicit F: Async[F]): Resource[F, F[Outcome[F, Throwable, A]]] =
    Async[F].backgroundOn(wrapped, ec)

  def backgroundOnExecutor(executor: Executor)(
      implicit F: Async[F]): Resource[F, F[Outcome[F, Throwable, A]]] =
    Async[F].backgroundOnExecutor(wrapped, executor)

  def syncStep[G[_]: Sync](limit: Int)(implicit F: Async[F]): G[Either[F[A], A]] =
    Async[F].syncStep[G, A](wrapped, limit)
}
