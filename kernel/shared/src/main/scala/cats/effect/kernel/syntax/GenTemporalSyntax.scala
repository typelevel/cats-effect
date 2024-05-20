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

import cats.effect.kernel.GenTemporal

import scala.concurrent.duration.{Duration, FiniteDuration}

import java.util.concurrent.TimeoutException

trait GenTemporalSyntax {

  implicit def genTemporalOps_[F[_], A](
      wrapped: F[A]
  ): GenTemporalOps_[F, A] =
    new GenTemporalOps_(wrapped)

  implicit def genTemporalOps[F[_], A, E](
      wrapped: F[A]
  )(implicit F: GenTemporal[F, E]): GenTemporalOps[F, A, E] = {
    val _ = F
    new GenTemporalOps(wrapped)
  }
}

final class GenTemporalOps_[F[_], A] private[syntax] (private val wrapped: F[A])
    extends AnyVal {

  def timeoutTo(duration: Duration, fallback: F[A])(implicit F: GenTemporal[F, _]): F[A] =
    F.timeoutTo(wrapped, duration, fallback)

  def timeoutTo(duration: FiniteDuration, fallback: F[A])(implicit F: GenTemporal[F, _]): F[A] =
    F.timeoutTo(wrapped, duration, fallback)

  def delayBy(time: Duration)(implicit F: GenTemporal[F, _]): F[A] =
    F.delayBy(wrapped, time)

  def delayBy(time: FiniteDuration)(implicit F: GenTemporal[F, _]): F[A] =
    F.delayBy(wrapped, time)

  def andWait(time: Duration)(implicit F: GenTemporal[F, _]): F[A] =
    F.andWait(wrapped, time)

  def andWait(time: FiniteDuration)(implicit F: GenTemporal[F, _]): F[A] =
    F.andWait(wrapped, time)
}

object GenTemporalOps_ extends GenTemporalOps_CompanionCompat

final class GenTemporalOps[F[_], A, E] private[syntax] (private val wrapped: F[A])
    extends AnyVal {

  def timeout(duration: FiniteDuration)(
      implicit F: GenTemporal[F, E],
      timeoutToE: TimeoutException <:< E
  ): F[A] = F.timeout(wrapped, duration)

  def timeout(duration: Duration)(
      implicit F: GenTemporal[F, E],
      timeoutToE: TimeoutException <:< E
  ): F[A] = F.timeout(wrapped, duration)

  def timeoutAndForget(duration: FiniteDuration)(
      implicit F: GenTemporal[F, E],
      timeoutToE: TimeoutException <:< E
  ): F[A] = F.timeoutAndForget(wrapped, duration)

  def timeoutAndForget(duration: Duration)(
      implicit F: GenTemporal[F, E],
      timeoutToE: TimeoutException <:< E
  ): F[A] = F.timeoutAndForget(wrapped, duration)
}

object GenTemporalOps extends GenTemporalOpsCompanionCompat
