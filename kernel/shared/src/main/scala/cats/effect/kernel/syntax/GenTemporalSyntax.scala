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

import cats.effect.kernel.GenTemporal

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeoutException

trait GenTemporalSyntax {

  implicit def genTemporalOps[F[_], A, E](
      wrapped: F[A]
  ): GenTemporalOps[F, A, E] =
    new GenTemporalOps(wrapped)
}

final class GenTemporalOps[F[_], A, E] private[syntax] (private[syntax] val wrapped: F[A])
    extends AnyVal {

  def timeoutTo(duration: FiniteDuration, fallback: F[A])(implicit F: GenTemporal[F, E]): F[A] =
    F.timeoutTo(wrapped, duration, fallback)

  def timeout(duration: FiniteDuration)(
      implicit F: GenTemporal[F, _ >: TimeoutException]): F[A] =
    F.timeout(wrapped, duration)

  def delayBy[E](time: FiniteDuration)(implicit F: GenTemporal[F, E]): F[A] =
    F.delayBy(wrapped, time)

  def andWait[E](time: FiniteDuration)(implicit F: GenTemporal[F, E]): F[A] =
    F.andWait(wrapped, time)
}
