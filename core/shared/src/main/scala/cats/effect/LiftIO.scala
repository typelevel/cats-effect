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

package cats
package effect

import simulacrum._

import cats.data.{EitherT, IorT, Kleisli, OptionT, ReaderWriterStateT, StateT, WriterT}

import scala.annotation.implicitNotFound

@typeclass
@implicitNotFound("""Cannot find implicit value for LiftIO[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait LiftIO[F[_]] {
  def liftIO[A](ioa: IO[A]): F[A]
}

object LiftIO {

  /**
   * [[LiftIO.liftIO]] as a natural transformation.
   */
  def liftK[F[_]: LiftIO]: IO ~> F = λ[IO ~> F](_.to[F])

  /**
   * [[LiftIO]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `LiftIO`.
   */
  implicit def catsEitherTLiftIO[F[_]: LiftIO: Functor, L]: LiftIO[EitherT[F, L, *]] =
    new EitherTLiftIO[F, L] { def F = LiftIO[F]; def FF = Functor[F] }

  /**
   * [[LiftIO]] instance built for `cats.data.Kleisli` values initialized
   * with any `F` data type that also implements `LiftIO`.
   */
  implicit def catsKleisliLiftIO[F[_]: LiftIO, R]: LiftIO[Kleisli[F, R, *]] =
    new KleisliLiftIO[F, R] { def F = LiftIO[F] }

  /**
   * [[LiftIO]] instance built for `cats.data.OptionT` values initialized
   * with any `F` data type that also implements `LiftIO`.
   */
  implicit def catsOptionTLiftIO[F[_]: LiftIO: Functor]: LiftIO[OptionT[F, *]] =
    new OptionTLiftIO[F] { def F = LiftIO[F]; def FF = Functor[F] }

  /**
   * [[LiftIO]] instance built for `cats.data.StateT` values initialized
   * with any `F` data type that also implements `LiftIO`.
   */
  implicit def catsStateTLiftIO[F[_]: LiftIO: Applicative, S]: LiftIO[StateT[F, S, *]] =
    new StateTLiftIO[F, S] { def F = LiftIO[F]; def FA = Applicative[F] }

  /**
   * [[LiftIO]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `LiftIO`.
   */
  implicit def catsWriterTLiftIO[F[_]: LiftIO: Applicative, L: Monoid]: LiftIO[WriterT[F, L, *]] =
    new WriterTLiftIO[F, L] { def F = LiftIO[F]; def FA = Applicative[F]; def L = Monoid[L] }

  /**
   * [[LiftIO]] instance built for `cats.data.IorT` values initialized
   * with any `F` data type that also implements `LiftIO`.
   */
  implicit def catsIorTLiftIO[F[_]: LiftIO: Applicative, L]: LiftIO[IorT[F, L, *]] =
    new IorTLiftIO[F, L] { def F = LiftIO[F]; def FA = Applicative[F] }

  /**
   * [[LiftIO]] instance built for `cats.data.ReaderWriterStateT` values initialized
   * with any `F` data type that also implements `LiftIO`.
   */
  implicit def catsReaderWriterStateTLiftIO[F[_]: LiftIO: Applicative, E, L: Monoid, S]
    : LiftIO[ReaderWriterStateT[F, E, L, S, *]] =
    new ReaderWriterStateTLiftIO[F, E, L, S] { def F = LiftIO[F]; def FA = Applicative[F]; def L = Monoid[L] }

  private[effect] trait EitherTLiftIO[F[_], L] extends LiftIO[EitherT[F, L, *]] {
    implicit protected def F: LiftIO[F]
    protected def FF: Functor[F]

    override def liftIO[A](ioa: IO[A]): EitherT[F, L, A] =
      EitherT.liftF(F.liftIO(ioa))(FF)
  }

  private[effect] trait KleisliLiftIO[F[_], R] extends LiftIO[Kleisli[F, R, *]] {
    implicit protected def F: LiftIO[F]

    override def liftIO[A](ioa: IO[A]): Kleisli[F, R, A] =
      Kleisli.liftF(F.liftIO(ioa))
  }

  private[effect] trait OptionTLiftIO[F[_]] extends LiftIO[OptionT[F, *]] {
    implicit protected def F: LiftIO[F]
    protected def FF: Functor[F]

    override def liftIO[A](ioa: IO[A]): OptionT[F, A] =
      OptionT.liftF(F.liftIO(ioa))(FF)
  }

  private[effect] trait StateTLiftIO[F[_], S] extends LiftIO[StateT[F, S, *]] {
    implicit protected def F: LiftIO[F]
    protected def FA: Applicative[F]

    override def liftIO[A](ioa: IO[A]): StateT[F, S, A] =
      StateT.liftF(F.liftIO(ioa))(FA)
  }

  private[effect] trait WriterTLiftIO[F[_], L] extends LiftIO[WriterT[F, L, *]] {
    implicit protected def F: LiftIO[F]
    implicit protected def L: Monoid[L]
    protected def FA: Applicative[F]

    override def liftIO[A](ioa: IO[A]): WriterT[F, L, A] =
      WriterT.liftF(F.liftIO(ioa))(L, FA)
  }

  private[effect] trait IorTLiftIO[F[_], L] extends LiftIO[IorT[F, L, *]] {
    implicit protected def F: LiftIO[F]
    protected def FA: Applicative[F]

    override def liftIO[A](ioa: IO[A]): IorT[F, L, A] =
      IorT.liftF(F.liftIO(ioa))(FA)
  }

  private[effect] trait ReaderWriterStateTLiftIO[F[_], E, L, S] extends LiftIO[ReaderWriterStateT[F, E, L, S, *]] {
    implicit protected def F: LiftIO[F]
    implicit protected def L: Monoid[L]
    protected def FA: Applicative[F]

    override def liftIO[A](ioa: IO[A]): ReaderWriterStateT[F, E, L, S, A] =
      ReaderWriterStateT.liftF(F.liftIO(ioa))(FA, L)
  }
}
