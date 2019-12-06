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

import cats.effect.Concurrent
import cats.effect.implicits._
import cats.{Monad, Parallel, Traverse}

trait ParallelNSyntax {
  implicit final def catsSyntaxParallelTraverseNConcurrent[T[_]: Traverse, A](
    ta: T[A]
  ): ParallelTraversableNConcurrentOps[T, A] =
    new ParallelTraversableNConcurrentOps[T, A](ta)

  implicit final def catsSyntaxParallelSequenceNConcurrent[T[_]: Traverse, M[_]: Monad, A](
    tma: T[M[A]]
  ): ParallelSequenceNConcurrentOps[T, M, A] = new ParallelSequenceNConcurrentOps[T, M, A](tma)
}

final class ParallelSequenceNConcurrentOps[T[_], M[_], A](private val tma: T[M[A]]) extends AnyVal {
  def parSequenceN(n: Long)(implicit M: Concurrent[M], T: Traverse[T], P: Parallel[M]): M[T[A]] =
    M.parSequenceN(n)(tma)
}

final class ParallelTraversableNConcurrentOps[T[_], A](private val ta: T[A]) extends AnyVal {
  def parTraverseN[M[_], B](n: Long)(f: A => M[B])(implicit M: Concurrent[M], T: Traverse[T], P: Parallel[M]): M[T[B]] =
    M.parTraverseN(n)(ta)(f)
}
