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

package cats.effect.kernel.instances

import cats.{~>, Align, Applicative, CommutativeApplicative, Functor, Monad, Parallel}
import cats.data.Ior
import cats.effect.kernel.{GenSpawn, ParallelF}
import cats.implicits._

trait GenSpawnInstances {

  implicit def parallelForGenSpawn[M[_], E](
      implicit M: GenSpawn[M, E]): Parallel.Aux[M, ParallelF[M, *]] =
    new Parallel[M] {
      type F[A] = ParallelF[M, A]

      def applicative: Applicative[F] = commutativeApplicativeForParallelF[M, E]

      def monad: Monad[M] = M

      def sequential: F ~> M =
        new (F ~> M) {
          def apply[A](fa: F[A]): M[A] = ParallelF.value[M, A](fa)
        }

      def parallel: M ~> F =
        new (M ~> F) {
          def apply[A](ma: M[A]): F[A] = ParallelF[M, A](ma)
        }
    }

  implicit def commutativeApplicativeForParallelF[F[_], E](
      implicit F: GenSpawn[F, E]): CommutativeApplicative[ParallelF[F, *]] =
    new CommutativeApplicative[ParallelF[F, *]] {

      final override def pure[A](a: A): ParallelF[F, A] = ParallelF(F.pure(a))

      final override def map2[A, B, Z](fa: ParallelF[F, A], fb: ParallelF[F, B])(
          f: (A, B) => Z): ParallelF[F, Z] = {
        ParallelF(
          F.both(ParallelF.value(fa), ParallelF.value(fb)).map { case (a, b) => f(a, b) })
      }

      final override def ap[A, B](ff: ParallelF[F, A => B])(
          fa: ParallelF[F, A]): ParallelF[F, B] =
        map2(ff, fa)(_(_))

      final override def product[A, B](
          fa: ParallelF[F, A],
          fb: ParallelF[F, B]): ParallelF[F, (A, B)] =
        map2(fa, fb)((_, _))

      final override def map[A, B](fa: ParallelF[F, A])(f: A => B): ParallelF[F, B] =
        ParallelF(ParallelF.value(fa).map(f))

      final override def unit: ParallelF[F, Unit] =
        ParallelF(F.unit)
    }

  implicit def alignForParallelF[F[_], E](implicit F: GenSpawn[F, E]): Align[ParallelF[F, *]] =
    new Align[ParallelF[F, *]] {

      override def functor: Functor[ParallelF[F, *]] = commutativeApplicativeForParallelF[F, E]

      override def align[A, B](
          fa: ParallelF[F, A],
          fb: ParallelF[F, B]): ParallelF[F, Ior[A, B]] =
        alignWith(fa, fb)(identity)

      override def alignWith[A, B, C](fa: ParallelF[F, A], fb: ParallelF[F, B])(
          f: Ior[A, B] => C): ParallelF[F, C] =
        ParallelF(
          (ParallelF.value(fa).attempt, ParallelF.value(fb).attempt)
            .parMapN((ea, eb) => catsStdInstancesForEither.alignWith(ea, eb)(f))
            .flatMap(F.fromEither)
        )

    }
}
