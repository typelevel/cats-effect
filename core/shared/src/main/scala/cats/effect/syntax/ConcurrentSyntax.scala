/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

import cats.{Parallel, Traverse}

import scala.concurrent.duration.FiniteDuration
import cats.effect.{Concurrent, Timer}

trait ConcurrentSyntax extends Concurrent.ToConcurrentOps {
  implicit def catsEffectSyntaxConcurrent[F[_], A](fa: F[A]): ConcurrentOps[F, A] =
    new ConcurrentOps[F, A](fa)

  implicit def catsEffectSyntaxConcurrentObj[F[_]](F: Concurrent[F]): ConcurrentObjOps[F] =
    new ConcurrentObjOps[F](F)
}

final class ConcurrentOps[F[_], A](val self: F[A]) extends AnyVal {
  def timeout(duration: FiniteDuration)(implicit F: Concurrent[F], timer: Timer[F]): F[A] =
    Concurrent.timeout[F, A](self, duration)

  def timeoutTo(duration: FiniteDuration, fallback: F[A])(implicit F: Concurrent[F], timer: Timer[F]): F[A] =
    Concurrent.timeoutTo(self, duration, fallback)
}

final class ConcurrentObjOps[F[_]](private val F: Concurrent[F]) extends AnyVal {

  /**
   * Like `Parallel.parTraverse`, but limits the degree of parallelism.
   */
  def parTraverseN[T[_], A, B](n: Long)(ta: T[A])(f: A => F[B])(implicit T: Traverse[T], P: Parallel[F]): F[T[B]] =
    Concurrent.parTraverseN(n)(ta)(f)(T, F, P)

  /**
   * Like `Parallel.parSequence`, but limits the degree of parallelism.
   */
  def parSequenceN[T[_], A](n: Long)(tma: T[F[A]])(implicit T: Traverse[T], P: Parallel[F]): F[T[A]] =
    Concurrent.parSequenceN(n)(tma)(T, F, P)
}
