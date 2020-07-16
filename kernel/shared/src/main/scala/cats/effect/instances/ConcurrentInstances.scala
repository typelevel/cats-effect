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

package cats.effect.instances

import cats.{~>, Applicative, Monad, Parallel}
import cats.effect.kernel.{Concurrent, ParallelF}

trait ConcurrentInstances {
  implicit def parallelForConcurrent[M[_], E](implicit M: Concurrent[M, E]): Parallel.Aux[M, ParallelF[M, *]] =
    new Parallel[M] {
      type F[A] = ParallelF[M, A]

      def applicative: Applicative[F] = ParallelF.applicativeForParallelF[M, E]

      def monad: Monad[M] = M

      def sequential: F ~> M = new (F ~> M) {
        def apply[A](fa: F[A]): M[A] = ParallelF.value[M, A](fa)
      }

      def parallel: M ~> F = new (M ~> F) {
        def apply[A](ma: M[A]): F[A] = ParallelF[M, A](ma)
      }

    }
}
