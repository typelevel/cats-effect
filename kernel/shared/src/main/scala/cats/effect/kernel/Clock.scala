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
import cats.data._

import scala.concurrent.duration.FiniteDuration

import cats.kernel.{Monoid, Semigroup}
import cats.{Defer, Monad}

trait Clock[F[_]] extends Applicative[F] {

  // (monotonic, monotonic).mapN(_ <= _)
  def monotonic: F[FiniteDuration]

  // lawless (unfortunately), but meant to represent current (when sequenced) system time
  def realTime: F[FiniteDuration]
}

object Clock {
  def apply[F[_]](implicit F: Clock[F]): F.type = F

  implicit def clockForOptionT[F[_]](
      implicit F0: Clock[F] with Monad[F]): Clock[OptionT[F, *]] =
    new OptionTClock[F] {
      implicit override def F: Clock[F] with Monad[F] = F0
    }

  implicit def clockForEitherT[F[_], E](
      implicit F0: Clock[F] with Monad[F]): Clock[EitherT[F, E, *]] =
    new EitherTClock[F, E] {
      implicit override def F: Clock[F] with Monad[F] = F0
    }

  implicit def clockForStateT[F[_], S](
      implicit F0: Clock[F] with Monad[F]): Clock[StateT[F, S, *]] =
    new StateTClock[F, S] {
      implicit override def F: Clock[F] with Monad[F] = F0
    }

  implicit def clockForWriterT[F[_], S](
      implicit F0: Clock[F] with Monad[F],
      S0: Monoid[S]): Clock[WriterT[F, S, *]] =
    new WriterTClock[F, S] {
      implicit override def F: Clock[F] with Monad[F] = F0

      implicit override def S: Monoid[S] = S0

    }

  implicit def clockForIorT[F[_], L](
      implicit F0: Clock[F] with Monad[F],
      L0: Semigroup[L]): Clock[IorT[F, L, *]] =
    new IorTClock[F, L] {
      implicit override def F: Clock[F] with Monad[F] = F0

      implicit override def L: Semigroup[L] = L0
    }

  implicit def clockForKleisli[F[_], R](
      implicit F0: Clock[F] with Monad[F]): Clock[Kleisli[F, R, *]] =
    new KleisliClock[F, R] {
      implicit override def F: Clock[F] with Monad[F] = F0
    }

  implicit def clockForContT[F[_], R](
      implicit F0: Clock[F] with Monad[F] with Defer[F]): Clock[ContT[F, R, *]] =
    new ContTClock[F, R] {
      implicit override def F: Clock[F] with Monad[F] with Defer[F] = F0
    }

  implicit def clockForReaderWriterStateT[F[_], R, L, S](
      implicit F0: Clock[F] with Monad[F],
      L0: Monoid[L]): Clock[ReaderWriterStateT[F, R, L, S, *]] =
    new ReaderWriterStateTClock[F, R, L, S] {
      implicit override def F: Clock[F] with Monad[F] = F0

      implicit override def L: Monoid[L] = L0
    }

  private[kernel] trait OptionTClock[F[_]] extends Clock[OptionT[F, *]] {
    implicit protected def F: Clock[F] with Monad[F]

    val delegate = OptionT.catsDataMonadForOptionT[F]

    override def ap[A, B](
        ff: OptionT[F, A => B]
    )(fa: OptionT[F, A]): OptionT[F, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): OptionT[F, A] = delegate.pure(x)

    override def monotonic: OptionT[F, FiniteDuration] =
      OptionT.liftF(F.monotonic)

    override def realTime: OptionT[F, FiniteDuration] = OptionT.liftF(F.realTime)
  }

  private[kernel] trait EitherTClock[F[_], E] extends Clock[EitherT[F, E, *]] {
    implicit protected def F: Clock[F] with Monad[F]

    val delegate = EitherT.catsDataMonadErrorForEitherT[F, E]

    override def ap[A, B](
        ff: EitherT[F, E, A => B]
    )(fa: EitherT[F, E, A]): EitherT[F, E, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): EitherT[F, E, A] = delegate.pure(x)

    override def monotonic: EitherT[F, E, FiniteDuration] =
      EitherT.liftF(F.monotonic)

    override def realTime: EitherT[F, E, FiniteDuration] = EitherT.liftF(F.realTime)
  }

  private[kernel] trait StateTClock[F[_], S] extends Clock[StateT[F, S, *]] {
    implicit protected def F: Clock[F] with Monad[F]

    val delegate = IndexedStateT.catsDataMonadForIndexedStateT[F, S]

    override def ap[A, B](
        ff: IndexedStateT[F, S, S, A => B]
    )(fa: IndexedStateT[F, S, S, A]): IndexedStateT[F, S, S, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): IndexedStateT[F, S, S, A] = delegate.pure(x)

    override def monotonic: IndexedStateT[F, S, S, FiniteDuration] =
      StateT.liftF(F.monotonic)

    override def realTime: IndexedStateT[F, S, S, FiniteDuration] =
      StateT.liftF(F.realTime)
  }

  private[kernel] trait WriterTClock[F[_], S] extends Clock[WriterT[F, S, *]] {
    implicit protected def F: Clock[F] with Monad[F]
    implicit protected def S: Monoid[S]

    val delegate = WriterT.catsDataMonadForWriterT[F, S]

    override def ap[A, B](
        ff: WriterT[F, S, A => B]
    )(fa: WriterT[F, S, A]): WriterT[F, S, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): WriterT[F, S, A] = delegate.pure(x)

    override def monotonic: WriterT[F, S, FiniteDuration] =
      WriterT.liftF(F.monotonic)

    override def realTime: WriterT[F, S, FiniteDuration] = WriterT.liftF(F.realTime)
  }

  private[kernel] trait IorTClock[F[_], L] extends Clock[IorT[F, L, *]] {
    implicit protected def F: Clock[F] with Monad[F]
    implicit protected def L: Semigroup[L]

    val delegate = IorT.catsDataMonadErrorForIorT[F, L]

    override def ap[A, B](
        ff: IorT[F, L, A => B]
    )(fa: IorT[F, L, A]): IorT[F, L, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): IorT[F, L, A] = delegate.pure(x)

    override def monotonic: IorT[F, L, FiniteDuration] = IorT.liftF(F.monotonic)

    override def realTime: IorT[F, L, FiniteDuration] = IorT.liftF(F.realTime)

  }

  private[kernel] trait KleisliClock[F[_], R] extends Clock[Kleisli[F, R, *]] {
    implicit protected def F: Clock[F] with Monad[F]

    val delegate = Kleisli.catsDataMonadForKleisli[F, R]

    override def ap[A, B](
        ff: Kleisli[F, R, A => B]
    )(fa: Kleisli[F, R, A]): Kleisli[F, R, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): Kleisli[F, R, A] = delegate.pure(x)

    override def monotonic: Kleisli[F, R, FiniteDuration] =
      Kleisli.liftF(F.monotonic)

    override def realTime: Kleisli[F, R, FiniteDuration] = Kleisli.liftF(F.realTime)

  }

  private[kernel] trait ContTClock[F[_], R] extends Clock[ContT[F, R, *]] {
    implicit protected def F: Clock[F] with Monad[F] with Defer[F]

    val delegate = ContT.catsDataContTMonad[F, R]

    override def ap[A, B](
        ff: ContT[F, R, A => B]
    )(fa: ContT[F, R, A]): ContT[F, R, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): ContT[F, R, A] = delegate.pure(x)

    override def monotonic: ContT[F, R, FiniteDuration] =
      ContT.liftF(F.monotonic)

    override def realTime: ContT[F, R, FiniteDuration] = ContT.liftF(F.realTime)

  }

  private[kernel] trait ReaderWriterStateTClock[F[_], R, L, S]
      extends Clock[ReaderWriterStateT[F, R, L, S, *]] {
    implicit protected def F: Clock[F] with Monad[F]

    implicit protected def L: Monoid[L]

    val delegate = IndexedReaderWriterStateT.catsDataMonadForRWST[F, R, L, S]

    override def ap[A, B](
        ff: ReaderWriterStateT[F, R, L, S, A => B]
    )(fa: ReaderWriterStateT[F, R, L, S, A]): ReaderWriterStateT[F, R, L, S, B] =
      delegate.ap(ff)(fa)

    override def pure[A](x: A): ReaderWriterStateT[F, R, L, S, A] = delegate.pure(x)

    override def monotonic: ReaderWriterStateT[F, R, L, S, FiniteDuration] =
      IndexedReaderWriterStateT.liftF(F.monotonic)

    override def realTime: ReaderWriterStateT[F, R, L, S, FiniteDuration] =
      IndexedReaderWriterStateT.liftF(F.realTime)

  }
}
