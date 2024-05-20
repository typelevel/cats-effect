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

package cats.effect

import cats.{~>, Applicative, FlatMap, Functor}
import cats.data.{ContT, EitherT, IorT, Kleisli, OptionT, ReaderWriterStateT, StateT, WriterT}
import cats.kernel.Monoid

trait LiftIO[F[_]] {
  def liftIO[A](ioa: IO[A]): F[A]
}

object LiftIO {

  def apply[F[_]](implicit F: LiftIO[F]): F.type = F

  /**
   * [[LiftIO.liftIO]] as a natural transformation
   */
  def liftK[F[_]](implicit F: LiftIO[F]): IO ~> F =
    new (IO ~> F) { def apply[A](fa: IO[A]): F[A] = F.liftIO(fa) }

  /**
   * [[LiftIO]] instance built for `cats.data.EitherT` values initialized with any `F` data type
   * that also implements `LiftIO`.
   */
  implicit def catsEitherTLiftIO[F[_], L](
      implicit F: LiftIO[F],
      FF: Functor[F]): LiftIO[EitherT[F, L, *]] =
    new LiftIO[EitherT[F, L, *]] {
      override def liftIO[A](ioa: IO[A]): EitherT[F, L, A] =
        EitherT.liftF(F.liftIO(ioa))
    }

  /**
   * [[LiftIO]] instance built for `cats.data.Kleisli` values initialized with any `F` data type
   * that also implements `LiftIO`.
   */
  implicit def catsKleisliLiftIO[F[_], R](implicit F: LiftIO[F]): LiftIO[Kleisli[F, R, *]] =
    new LiftIO[Kleisli[F, R, *]] {
      override def liftIO[A](ioa: IO[A]): Kleisli[F, R, A] =
        Kleisli.liftF(F.liftIO(ioa))
    }

  /**
   * [[LiftIO]] instance built for `cats.data.OptionT` values initialized with any `F` data type
   * that also implements `LiftIO`.
   */
  implicit def catsOptionTLiftIO[F[_]](
      implicit F: LiftIO[F],
      FF: Functor[F]): LiftIO[OptionT[F, *]] =
    new LiftIO[OptionT[F, *]] {
      override def liftIO[A](ioa: IO[A]): OptionT[F, A] =
        OptionT.liftF(F.liftIO(ioa))
    }

  /**
   * [[LiftIO]] instance built for `cats.data.StateT` values initialized with any `F` data type
   * that also implements `LiftIO`.
   */
  implicit def catsStateTLiftIO[F[_], S](
      implicit F: LiftIO[F],
      FA: Applicative[F]): LiftIO[StateT[F, S, *]] =
    new LiftIO[StateT[F, S, *]] {
      override def liftIO[A](ioa: IO[A]): StateT[F, S, A] =
        StateT.liftF(F.liftIO(ioa))
    }

  /**
   * [[LiftIO]] instance built for `cats.data.WriterT` values initialized with any `F` data type
   * that also implements `LiftIO`.
   */
  implicit def catsWriterTLiftIO[F[_], L](
      implicit F: LiftIO[F],
      FA: Applicative[F],
      L: Monoid[L]): LiftIO[WriterT[F, L, *]] =
    new LiftIO[WriterT[F, L, *]] {
      override def liftIO[A](ioa: IO[A]): WriterT[F, L, A] =
        WriterT.liftF(F.liftIO(ioa))
    }

  /**
   * [[LiftIO]] instance built for `cats.data.IorT` values initialized with any `F` data type
   * that also implements `LiftIO`.
   */
  implicit def catsIorTLiftIO[F[_], L](
      implicit F: LiftIO[F],
      FA: Applicative[F]): LiftIO[IorT[F, L, *]] =
    new LiftIO[IorT[F, L, *]] {
      override def liftIO[A](ioa: IO[A]): IorT[F, L, A] =
        IorT.liftF(F.liftIO(ioa))
    }

  /**
   * [[LiftIO]] instance built for `cats.data.ReaderWriterStateT` values initialized with any
   * `F` data type that also implements `LiftIO`.
   */
  implicit def catsReaderWriterStateTLiftIO[F[_], E, L, S](
      implicit F: LiftIO[F],
      FA: Applicative[F],
      L: Monoid[L]): LiftIO[ReaderWriterStateT[F, E, L, S, *]] =
    new LiftIO[ReaderWriterStateT[F, E, L, S, *]] {
      override def liftIO[A](ioa: IO[A]): ReaderWriterStateT[F, E, L, S, A] =
        ReaderWriterStateT.liftF(F.liftIO(ioa))
    }

  /**
   * [[LiftIO]] instance built for `cats.data.ContT` values initialized with any `F` data type
   * that also implements `LiftIO`.
   */
  implicit def catsContTLiftIO[F[_], R](
      implicit F: LiftIO[F],
      FF: FlatMap[F]): LiftIO[ContT[F, R, *]] =
    new LiftIO[ContT[F, R, *]] {
      override def liftIO[A](ioa: IO[A]): ContT[F, R, A] =
        ContT.liftF(F.liftIO(ioa))
    }

  /**
   * [[LiftIO]] instance for [[IO]] values.
   */
  implicit val ioLiftIO: LiftIO[IO] =
    new LiftIO[IO] { override def liftIO[A](ioa: IO[A]): IO[A] = ioa }

  /**
   * [[LiftIO]] instance for [[Resource]] values.
   */
  implicit def catsEffectLiftIOForResource[F[_]](
      implicit F00: LiftIO[F],
      F10: Applicative[F]): LiftIO[Resource[F, *]] =
    new ResourceLiftIO[F] {
      def F0 = F00
      def F1 = F10
    }

  abstract private class ResourceLiftIO[F[_]] extends LiftIO[Resource[F, *]] {
    implicit protected def F0: LiftIO[F]
    implicit protected def F1: Applicative[F]

    def liftIO[A](ioa: IO[A]): Resource[F, A] =
      Resource.eval(F0.liftIO(ioa))
  }
}
