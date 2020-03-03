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

import cats.effect.Async
import cats.{Parallel, Traverse}

trait AsyncSyntax extends Async.ToAsyncOps {
  implicit def catsEffectSyntaxAsyncObj[F[_]](F: Async[F]): AsyncObjOps[F] =
    new AsyncObjOps[F](F)
}

final class AsyncObjOps[F[_]](private val F: Async[F]) extends AnyVal {

  /**
   * Like `Parallel.parTraverse`, but limits the degree of parallelism.
   */
  def parTraverseN[T[_], A, B](n: Long)(ta: T[A])(f: A => F[B])(implicit T: Traverse[T], P: Parallel[F]): F[T[B]] =
    Async.parTraverseN(n)(ta)(f)(T, F, P)

  /**
   * Like `Parallel.parSequence`, but limits the degree of parallelism.
   */
  def parSequenceN[T[_], A](n: Long)(tma: T[F[A]])(implicit T: Traverse[T], P: Parallel[F]): F[T[A]] =
    Async.parSequenceN(n)(tma)(T, F, P)
}
