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

import scala.concurrent.duration.FiniteDuration

import java.util.concurrent.TimeoutException

private[syntax] trait GenTemporalOps_CompanionCompat {

  @deprecated("Preserved for binary-compatibility", "3.4.0")
  def timeoutTo$extension[F[_], A](
      wrapped: F[A],
      duration: FiniteDuration,
      fallback: F[A],
      F: GenTemporal[F, _]): F[A] =
    F.timeoutTo(wrapped, duration, fallback)

  @deprecated("Preserved for binary-compatibility", "3.4.0")
  def delayBy$extension[F[_], A](
      wrapped: F[A],
      duration: FiniteDuration,
      F: GenTemporal[F, _]): F[A] =
    F.delayBy(wrapped, duration)

  @deprecated("Preserved for binary-compatibility", "3.4.0")
  def andWait$extension[F[_], A](
      wrapped: F[A],
      duration: FiniteDuration,
      F: GenTemporal[F, _]): F[A] =
    F.andWait(wrapped, duration)

}

private[syntax] trait GenTemporalOpsCompanionCompat {

  @deprecated("Preserved for binary-compatibility", "3.4.0")
  def timeout$extension[F[_], A, E](
      wrapped: F[A],
      duration: FiniteDuration,
      F: GenTemporal[F, E],
      timeoutToE: TimeoutException <:< E): F[A] = F.timeout(wrapped, duration)(timeoutToE)

  @deprecated("Preserved for binary-compatibility", "3.4.0")
  def timeoutAndForget$extension[F[_], A, E](
      wrapped: F[A],
      duration: FiniteDuration,
      F: GenTemporal[F, E],
      timeoutToE: TimeoutException <:< E): F[A] =
    F.timeoutAndForget(wrapped, duration)(timeoutToE)

}
