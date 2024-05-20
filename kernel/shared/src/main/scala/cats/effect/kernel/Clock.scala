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

package cats.effect.kernel

import cats.{Applicative, Defer, Monad}
import cats.data._
import cats.kernel.{Monoid, Semigroup}

import scala.concurrent.duration.FiniteDuration

/**
 * A typeclass which encodes various notions of time. Analogous to some of the time functions
 * exposed by `java.lang.System`.
 */
trait Clock[F[_]] extends ClockPlatform[F] with Serializable {

  def applicative: Applicative[F]

  /**
   * Monotonic time subject to the law that (monotonic, monotonic).mapN(_ <= _)
   *
   * Analogous to `java.lang.System.nanoTime`.
   */
  def monotonic: F[FiniteDuration]

  /**
   * A representation of the current system time
   *
   * Analogous to `java.lang.System.currentTimeMillis`.
   */
  def realTime: F[FiniteDuration]

  /**
   * Returns an effect that completes with the result of the source together with the duration
   * that it took to complete.
   *
   * @param fa
   *   The effect which we wish to time the execution of
   */
  def timed[A](fa: F[A]): F[(FiniteDuration, A)] =
    applicative.map3(monotonic, fa, monotonic)((startTime, a, endTime) =>
      (endTime.minus(startTime), a))
}

object Clock {

  def apply[F[_]](implicit F: Clock[F]): F.type = F

  implicit def clockForOptionT[F[_]](
      implicit F0: Monad[F],
      C0: Clock[F]): Clock[OptionT[F, *]] =
    new OptionTClock[F] {
      def applicative: Applicative[OptionT[F, *]] = OptionT.catsDataMonadForOptionT(F)
      implicit override def F: Monad[F] = F0
      implicit override def C: Clock[F] = C0
    }

  implicit def clockForEitherT[F[_], E](
      implicit F0: Monad[F],
      C0: Clock[F]): Clock[EitherT[F, E, *]] =
    new EitherTClock[F, E] {
      def applicative: Applicative[EitherT[F, E, *]] = EitherT.catsDataMonadErrorForEitherT(F)
      implicit override def F: Monad[F] = F0
      implicit override def C: Clock[F] = C0
    }

  implicit def clockForStateT[F[_], S](
      implicit F0: Monad[F],
      C0: Clock[F]): Clock[StateT[F, S, *]] =
    new StateTClock[F, S] {
      def applicative: Applicative[IndexedStateT[F, S, S, *]] =
        IndexedStateT.catsDataMonadForIndexedStateT(F)
      implicit override def F: Monad[F] = F0
      implicit override def C: Clock[F] = C0
    }

  implicit def clockForWriterT[F[_], L](
      implicit F0: Monad[F],
      C0: Clock[F],
      L0: Monoid[L]): Clock[WriterT[F, L, *]] =
    new WriterTClock[F, L] {
      def applicative: Applicative[WriterT[F, L, *]] = WriterT.catsDataMonadForWriterT(F, L)
      implicit override def F: Monad[F] = F0
      implicit override def C: Clock[F] = C0

      implicit override def L: Monoid[L] = L0

    }

  implicit def clockForIorT[F[_], L](
      implicit F0: Monad[F],
      C0: Clock[F],
      L0: Semigroup[L]): Clock[IorT[F, L, *]] =
    new IorTClock[F, L] {
      def applicative: Applicative[IorT[F, L, *]] = IorT.catsDataMonadErrorForIorT(F, L)
      implicit override def F: Monad[F] = F0
      implicit override def C: Clock[F] = C0

      implicit override def L: Semigroup[L] = L0
    }

  implicit def clockForKleisli[F[_], R](
      implicit F0: Monad[F],
      C0: Clock[F]): Clock[Kleisli[F, R, *]] =
    new KleisliClock[F, R] {
      def applicative: Applicative[Kleisli[F, R, *]] = Kleisli.catsDataMonadForKleisli(F)
      implicit override def F: Monad[F] = F0
      implicit override def C: Clock[F] = C0
    }

  implicit def clockForContT[F[_], R](
      implicit F0: Monad[F],
      C0: Clock[F],
      D0: Defer[F]): Clock[ContT[F, R, *]] =
    new ContTClock[F, R] {
      def applicative: Applicative[ContT[F, R, *]] = ContT.catsDataContTMonad(D)
      implicit override def F: Monad[F] = F0
      implicit override def C: Clock[F] = C0
      implicit override def D: Defer[F] = D0
    }

  implicit def clockForReaderWriterStateT[F[_], R, L, S](
      implicit F0: Monad[F],
      C0: Clock[F],
      L0: Monoid[L]): Clock[ReaderWriterStateT[F, R, L, S, *]] =
    new ReaderWriterStateTClock[F, R, L, S] {
      def applicative: Applicative[ReaderWriterStateT[F, R, L, S, *]] =
        IndexedReaderWriterStateT.catsDataMonadForRWST(F, L)
      implicit override def F: Monad[F] = F0
      implicit override def C: Clock[F] = C0

      implicit override def L: Monoid[L] = L0
    }

  private[kernel] trait OptionTClock[F[_]] extends Clock[OptionT[F, *]] {
    implicit protected def F: Monad[F]
    implicit protected def C: Clock[F]

    override def monotonic: OptionT[F, FiniteDuration] =
      OptionT.liftF(C.monotonic)

    override def realTime: OptionT[F, FiniteDuration] = OptionT.liftF(C.realTime)
  }

  private[kernel] trait EitherTClock[F[_], E] extends Clock[EitherT[F, E, *]] {
    implicit protected def F: Monad[F]
    implicit protected def C: Clock[F]

    override def monotonic: EitherT[F, E, FiniteDuration] =
      EitherT.liftF(C.monotonic)

    override def realTime: EitherT[F, E, FiniteDuration] = EitherT.liftF(C.realTime)
  }

  private[kernel] trait StateTClock[F[_], S] extends Clock[StateT[F, S, *]] {
    implicit protected def F: Monad[F]
    implicit protected def C: Clock[F]

    override def monotonic: IndexedStateT[F, S, S, FiniteDuration] =
      StateT.liftF(C.monotonic)

    override def realTime: IndexedStateT[F, S, S, FiniteDuration] =
      StateT.liftF(C.realTime)
  }

  private[kernel] trait WriterTClock[F[_], L] extends Clock[WriterT[F, L, *]] {
    implicit protected def F: Monad[F]
    implicit protected def C: Clock[F]
    implicit protected def L: Monoid[L]

    override def monotonic: WriterT[F, L, FiniteDuration] =
      WriterT.liftF(C.monotonic)

    override def realTime: WriterT[F, L, FiniteDuration] = WriterT.liftF(C.realTime)
  }

  private[kernel] trait IorTClock[F[_], L] extends Clock[IorT[F, L, *]] {
    implicit protected def F: Monad[F]
    implicit protected def C: Clock[F]
    implicit protected def L: Semigroup[L]

    override def monotonic: IorT[F, L, FiniteDuration] = IorT.liftF(C.monotonic)

    override def realTime: IorT[F, L, FiniteDuration] = IorT.liftF(C.realTime)

  }

  private[kernel] trait KleisliClock[F[_], R] extends Clock[Kleisli[F, R, *]] {
    implicit protected def F: Monad[F]
    implicit protected def C: Clock[F]

    override def monotonic: Kleisli[F, R, FiniteDuration] =
      Kleisli.liftF(C.monotonic)

    override def realTime: Kleisli[F, R, FiniteDuration] = Kleisli.liftF(C.realTime)

  }

  private[kernel] trait ContTClock[F[_], R] extends Clock[ContT[F, R, *]] {
    implicit protected def F: Monad[F]
    implicit protected def C: Clock[F]
    implicit protected def D: Defer[F]

    override def monotonic: ContT[F, R, FiniteDuration] =
      ContT.liftF(C.monotonic)

    override def realTime: ContT[F, R, FiniteDuration] = ContT.liftF(C.realTime)

  }

  private[kernel] trait ReaderWriterStateTClock[F[_], R, L, S]
      extends Clock[ReaderWriterStateT[F, R, L, S, *]] {
    implicit protected def F: Monad[F]
    implicit protected def C: Clock[F]
    implicit protected def L: Monoid[L]

    override def monotonic: ReaderWriterStateT[F, R, L, S, FiniteDuration] =
      IndexedReaderWriterStateT.liftF(C.monotonic)

    override def realTime: ReaderWriterStateT[F, R, L, S, FiniteDuration] =
      IndexedReaderWriterStateT.liftF(C.realTime)

  }
}
