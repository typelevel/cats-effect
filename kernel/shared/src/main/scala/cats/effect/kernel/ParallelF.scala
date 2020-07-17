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

package cats.effect.kernel

import cats.{Align, Applicative, Functor, Parallel}
import cats.implicits._
import cats.data.Ior
import cats.effect.kernel.Par.instance
import cats.effect.implicits._

//See https://failex.blogspot.com/2017/04/the-high-cost-of-anyval-subclasses.html
object Par {
  sealed abstract class ParallelFImpl {
    type T[F[_], A]
    def apply[F[_], A](fa: F[A]): T[F, A]
    def value[F[_], A](t: T[F, A]): F[A]
  }

  type ParallelF[F[_], A] = instance.T[F, A]

  object ParallelF {

    def apply[F[_], A](fa: F[A]): ParallelF[F, A] = instance[F, A](fa)

    def value[F[_], A](t: ParallelF[F, A]): F[A] = instance.value(t)

    implicit def applicativeForParallelF[F[_], E](
        implicit F: Concurrent[F, E]): Applicative[ParallelF[F, *]] =
      new Applicative[ParallelF[F, *]] {

        def pure[A](a: A): ParallelF[F, A] = ParallelF(F.pure(a))

        def ap[A, B](ff: ParallelF[F, A => B])(fa: ParallelF[F, A]): ParallelF[F, B] =
          ParallelF(
            F.both(ParallelF.value(ff), ParallelF.value(fa)).map {
              case (f, a) => f(a)
            }
          )

      }

    implicit def alignForParallelF[F[_], E](
        implicit F: Concurrent[F, E]): Align[ParallelF[F, *]] =
      new Align[ParallelF[F, *]] {

        override def functor: Functor[ParallelF[F, *]] = applicativeForParallelF[F, E]

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

  val instance: ParallelFImpl = new ParallelFImpl {
    type T[F[_], A] = F[A]

    override def apply[F[_], A](fa: F[A]): T[F, A] = fa

    override def value[F[_], A](t: T[F, A]): F[A] = t

  }

}
