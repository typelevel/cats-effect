/*
 * Copyright 2020-2021 Typelevel
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

package cats.effect.std

import cats.Monad
import cats.syntax.all._
import cats.data.OptionT
import cats.data.WriterT
import cats.kernel.Monoid

/**
 * Encodes the ability to peek into a computation's product/coproduct
 * structure to decide whether it can be resumed or should be short-stopped.
 *
 * If the computation can be resumed (typically when all monadic layers
 * have been applied successfully), Right is yielded,
 * with a `F[Unit]` keeping track of some potential product components
 * in the monadic effect.
 *
 * If the computation cannot be resumed because of some non-successful
 * coproduct component in the monadic effect, Left is yielded.
 */
trait Resume[F[_]] {

  def resume[A](fa: F[A]): F[Either[F[A], (F[Unit], A)]]

}

object Resume extends LowPriorityResumeInstances {

  implicit def optionTResume[F[_]](implicit F: Monad[F], R: Resume[F]): Resume[OptionT[F, *]] =
    new Resume[OptionT[F, *]] {
      def resume[A](
          fa: OptionT[F, A]): OptionT[F, Either[OptionT[F, A], (OptionT[F, Unit], A)]] =
        OptionT.liftF {
          R.resume(fa.value).map {
            case Left(fa) => Left(OptionT(fa))
            case Right((funit, None)) => Left(OptionT(funit.as(None)))
            case Right((funit, Some(value))) => Right(OptionT.liftF(funit), value)
          }
        }
    }

  implicit def writerTResume[F[_]: Monad, L: Monoid](
      implicit R: Resume[F]): Resume[WriterT[F, L, *]] =
    new Resume[WriterT[F, L, *]] {
      def resume[A](fa: WriterT[F, L, A])
          : WriterT[F, L, Either[WriterT[F, L, A], (WriterT[F, L, Unit], A)]] =
        WriterT.liftF {
          R.resume(fa.run).map {
            case Left(value) => Left(WriterT(value))
            case Right((funit, (log, value))) =>
              val w = WriterT(funit.map(log -> _))
              Right((w, value))
          }
        }
    }

}

trait LowPriorityResumeInstances {

  implicit def defaultMonadicResume[F[_]](implicit F: Monad[F]): Resume[F] = new Resume[F] {
    def resume[A](fa: F[A]): F[Either[F[A], (F[Unit], A)]] = fa.map { a => Right((F.unit, a)) }
  }

}
