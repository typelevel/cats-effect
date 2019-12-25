/*
 * Copyright 2019 Daniel Spiewak
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

package ce3

import cats.~>
import cats.data.EitherK

import scala.util.{Either, Left,Right}

// like InjectK, but provides the remainder when deconstructing
sealed trait ProjectK[F[_], G[_], R[_]] extends (G ~> λ[α => Either[F[α], R[α]]])

object ProjectK {

  implicit def base[F[_], R[_]]: ProjectK[F, EitherK[F, R, ?], R] =
    new ProjectK[F, EitherK[F, R, ?], R] {
      def apply[A](ek: EitherK[F, R, A]): Either[F[A], R[A]] =
        ek.run
    }

  implicit def induct[F[_], G[_], K[_], R[_]](
      implicit P: ProjectK[F, G, R])
      : ProjectK[F, EitherK[K, G, ?], EitherK[K, R, ?]] =
    new ProjectK[F, EitherK[K, G, ?], EitherK[K, R, ?]] {
      def apply[A](ek: EitherK[K, G, A]): Either[F[A], EitherK[K, R, A]] =
        ek.run match {
          case Left(ka) =>
            Right(EitherK(Left(ka)))

          case Right(ga) =>
            P(ga).map(ra => EitherK(Right(ra)))
        }
    }
}
