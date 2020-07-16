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

import cats.Applicative
import cats.implicits._

final case class ParallelF[F[_], A](value: F[A])

object ParallelF {

  implicit def applicativeForParallelF[F[_], E](implicit F: Concurrent[F, E]): Applicative[ParallelF[F, *]] =
    new Applicative[ParallelF[F, *]] {

      def pure[A](a: A): ParallelF[F, A] = ParallelF(F.pure(a))

      def ap[A, B](ff: ParallelF[F, A => B])(fa: ParallelF[F, A]): ParallelF[F, B] =
        ParallelF(
          F.both(ff.value, fa.value)
            .map {
              case (f, a) => f(a)
            }
        )

    }

}
