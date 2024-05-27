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

import cats.{Foldable, Traverse}
import cats.effect.kernel.GenConcurrent

trait GenConcurrentSyntax {

  implicit def genConcurrentOps_[F[_], A](wrapped: F[A]): GenConcurrentOps_[F, A] =
    new GenConcurrentOps_(wrapped)

  implicit def concurrentParTraverseOps[T[_], A](
      wrapped: T[A]
  ): ConcurrentParTraverseNOps[T, A] =
    new ConcurrentParTraverseNOps(wrapped)

  implicit def concurrentParSequenceOps[T[_], F[_], A](
      wrapped: T[F[A]]
  ): ConcurrentParSequenceNOps[T, F, A] =
    new ConcurrentParSequenceNOps(wrapped)

}

final class GenConcurrentOps_[F[_], A] private[syntax] (private val wrapped: F[A])
    extends AnyVal {
  def memoize(implicit F: GenConcurrent[F, _]): F[F[A]] =
    F.memoize(wrapped)

  def parReplicateAN(n: Int)(replicas: Int)(implicit F: GenConcurrent[F, _]): F[List[A]] =
    F.parReplicateAN(n)(replicas, wrapped)
}

final class ConcurrentParTraverseNOps[T[_], A] private[syntax] (
    private val wrapped: T[A]
) extends AnyVal {
  def parTraverseN[F[_], B](n: Int)(
      f: A => F[B]
  )(implicit T: Traverse[T], F: GenConcurrent[F, _]): F[T[B]] =
    F.parTraverseN(n)(wrapped)(f)

  def parTraverseN_[F[_], B](n: Int)(
      f: A => F[B]
  )(implicit T: Foldable[T], F: GenConcurrent[F, _]): F[Unit] =
    F.parTraverseN_(n)(wrapped)(f)
}

final class ConcurrentParSequenceNOps[T[_], F[_], A] private[syntax] (
    private val wrapped: T[F[A]]
) extends AnyVal {
  def parSequenceN(n: Int)(implicit T: Traverse[T], F: GenConcurrent[F, _]): F[T[A]] =
    F.parSequenceN(n)(wrapped)

  def parSequenceN_(n: Int)(implicit T: Foldable[T], F: GenConcurrent[F, _]): F[Unit] =
    F.parSequenceN_(n)(wrapped)
}
