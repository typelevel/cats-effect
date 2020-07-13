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

import cats.effect.kernel.Temporal

import scala.concurrent.duration.FiniteDuration

trait TemporalSyntax {
  implicit def temporalOps[F[_], A, E](
    wrapped: F[A]
  ): ConcurrentOps[F, A, E] =
    new ConcurrentOps(wrapped)
}

final class TemporalOps[F[_], A, E](val wrapped: F[A]) extends AnyVal {

  def timeoutTo(duration: FiniteDuration, fallback: F[A])(implicit F: Temporal[F, E]): F[A] =
    Temporal.timeoutTo(wrapped, duration, fallback)

  def timeout(duration: FiniteDuration)(implicit F: Temporal[F, Throwable]): F[A] = Temporal.timeout(wrapped, duration)

}
