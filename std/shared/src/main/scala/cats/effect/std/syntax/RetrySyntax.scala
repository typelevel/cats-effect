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

package cats.effect.std.syntax

import cats.effect.kernel.GenTemporal
import cats.effect.std.Retry

trait RetrySyntax {
  implicit def retryOps[F[_], A](wrapped: F[A]): RetryOps[F, A] =
    new RetryOps(wrapped)
}

final class RetryOps[F[_], A] private[syntax] (private val fa: F[A]) extends AnyVal {

  def retry[E](policy: Retry[F, E])(implicit F: GenTemporal[F, E]): F[A] =
    Retry.retry(policy)(fa)

  def retry[E](
      policy: Retry[F, E],
      onRetry: (Retry.Status, E, Retry.Decision) => F[Unit]
  )(implicit F: GenTemporal[F, E]): F[A] =
    Retry.retry(policy, onRetry)(fa)

}
