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
import cats.data.Kleisli
import cats.kernel.Monoid
import cats.data.IorT
import cats.data.Ior
import cats.data.EitherT

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

  implicit def optionTResume[F[_]](implicit F: Monad[F], RF: Resume[F]): Resume[OptionT[F, *]] =
    new Resume[OptionT[F, *]] {
      def resume[A](
          fa: OptionT[F, A]): OptionT[F, Either[OptionT[F, A], (OptionT[F, Unit], A)]] =
        OptionT.liftF {
          RF.resume(fa.value).map {
            case Left(stopped) => Left(OptionT(stopped))
            case Right((funit, None)) => Left(OptionT(funit.as(None)))
            case Right((funit, Some(value))) => Right(OptionT.liftF(funit), value)
          }
        }
    }

  implicit def writerTResume[F[_]: Monad, L: Monoid](
      implicit RF: Resume[F]): Resume[WriterT[F, L, *]] =
    new Resume[WriterT[F, L, *]] {
      def resume[A](fa: WriterT[F, L, A])
          : WriterT[F, L, Either[WriterT[F, L, A], (WriterT[F, L, Unit], A)]] =
        WriterT.liftF {
          RF.resume(fa.run).map {
            case Left(stopped) => Left(WriterT(stopped))
            case Right((funit, (log, value))) =>
              val w = WriterT(funit.map(log -> _))
              Right((w, value))
          }
        }
    }

  implicit def kleisliResume[F[_]: Monad, R](implicit RF: Resume[F]): Resume[Kleisli[F, R, *]] =
    new Resume[Kleisli[F, R, *]] {
      def resume[A](fa: Kleisli[F, R, A])
          : Kleisli[F, R, Either[Kleisli[F, R, A], (Kleisli[F, R, Unit], A)]] = Kleisli { r =>
        RF.resume(fa.run(r)).map {
          case Left(stopped) => Left(Kleisli.liftF[F, R, A](stopped))
          case Right((funit, value)) => Right((Kleisli.liftF(funit), value))
        }
      }
    }

  implicit def iorTResume[F[_]: Monad, E](implicit RF: Resume[F]): Resume[IorT[F, E, *]] =
    new Resume[IorT[F, E, *]] {
      def resume[A](
          fa: IorT[F, E, A]): IorT[F, E, Either[IorT[F, E, A], (IorT[F, E, Unit], A)]] =
        IorT.liftF {
          RF.resume(fa.value).map {
            case Left(stopped) => Left(IorT(stopped))
            case Right((funit, Ior.Right(value))) => Right((IorT.liftF(funit), value))
            case Right((funit, left @ Ior.Left(_))) => Left(IorT(funit.as(left)))
            case Right((funit, Ior.Both(e, value))) =>
              Right(IorT(funit.as(Ior.Both(e, ()))), value)
          }
        }
    }

  implicit def eitherTResume[F[_]: Monad, E](implicit RF: Resume[F]): Resume[EitherT[F, E, *]] =
    new Resume[EitherT[F, E, *]] {
      def resume[A](fa: EitherT[F, E, A])
          : EitherT[F, E, Either[EitherT[F, E, A], (EitherT[F, E, Unit], A)]] =
        EitherT.liftF {
          RF.resume(fa.value).map {
            case Left(stopped) => Left(EitherT(stopped))
            case Right((funit, Right(value))) => Right((EitherT.liftF(funit), value))
            case Right((funit, left @ Left(_))) => Left(EitherT(funit.as(left)))
          }
        }
    }

}

trait LowPriorityResumeInstances {

  implicit def defaultMonadicResume[F[_]](implicit F: Monad[F]): Resume[F] = new Resume[F] {
    def resume[A](fa: F[A]): F[Either[F[A], (F[Unit], A)]] = fa.map { a => Right((F.unit, a)) }
  }

}
