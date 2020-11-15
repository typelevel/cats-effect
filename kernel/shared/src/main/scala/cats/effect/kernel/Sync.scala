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

import cats.{Defer, MonadError, Monoid, Semigroup}
import cats.data.{
  EitherT,
  IndexedReaderWriterStateT,
  IndexedStateT,
  IorT,
  Kleisli,
  OptionT,
  ReaderWriterStateT,
  StateT,
  WriterT
}

trait Sync[F[_]] extends MonadError[F, Throwable] with Clock[F] with Defer[F] {

  private[this] val Delay = Sync.Type.Delay
  private[this] val Blocking = Sync.Type.Blocking
  private[this] val InterruptibleOnce = Sync.Type.InterruptibleOnce
  private[this] val InterruptibleMany = Sync.Type.InterruptibleMany

  def delay[A](thunk: => A): F[A] =
    suspend(Delay)(thunk)

  def defer[A](thunk: => F[A]): F[A] =
    flatMap(delay(thunk))(x => x)

  def blocking[A](thunk: => A): F[A] =
    suspend(Blocking)(thunk)

  def interruptible[A](many: Boolean)(thunk: => A): F[A] =
    suspend(if (many) InterruptibleOnce else InterruptibleMany)(thunk)

  def suspend[A](hint: Sync.Type)(thunk: => A): F[A]
}

object Sync {

  def apply[F[_]](implicit F: Sync[F]): F.type = F

  implicit def syncForOptionT[F[_]](implicit F0: Sync[F]): Sync[OptionT[F, *]] =
    new OptionTSync[F] {
      implicit def F: Sync[F] = F0
    }

  implicit def syncForEitherT[F[_], E](implicit F0: Sync[F]): Sync[EitherT[F, E, *]] =
    new EitherTSync[F, E] {
      implicit def F: Sync[F] = F0
    }

  implicit def syncForStateT[F[_], S](implicit F0: Sync[F]): Sync[StateT[F, S, *]] =
    new StateTSync[F, S] {
      implicit def F: Sync[F] = F0
    }

  implicit def syncForWriterT[F[_], L](
      implicit F0: Sync[F],
      L0: Monoid[L]): Sync[WriterT[F, L, *]] =
    new WriterTSync[F, L] {
      implicit def F: Sync[F] = F0

      implicit def L: Monoid[L] = L0
    }

  implicit def syncForIorT[F[_], L](
      implicit F0: Sync[F],
      L0: Semigroup[L]): Sync[IorT[F, L, *]] =
    new IorTSync[F, L] {
      implicit def F: Sync[F] = F0

      implicit def L: Semigroup[L] = L0
    }

  implicit def syncForKleisli[F[_], R](implicit F0: Sync[F]): Sync[Kleisli[F, R, *]] =
    new KleisliSync[F, R] {
      implicit def F: Sync[F] = F0
    }

  implicit def syncForReaderWriterStateT[F[_], R, L, S](
      implicit F0: Sync[F],
      L0: Monoid[L]): Sync[ReaderWriterStateT[F, R, L, S, *]] =
    new ReaderWriterStateTSync[F, R, L, S] {
      implicit override def F: Sync[F] = F0

      implicit override def L: Monoid[L] = L0
    }

  private[effect] trait OptionTSync[F[_]]
      extends Sync[OptionT[F, *]]
      with Clock.OptionTClock[F] {

    implicit protected def F: Sync[F]
    protected def C = F

    protected def delegate: MonadError[OptionT[F, *], Throwable] =
      OptionT.catsDataMonadErrorForOptionT[F, Throwable]

    def applicative = this

    def pure[A](a: A): OptionT[F, A] = delegate.pure(a)

    def handleErrorWith[A](fa: OptionT[F, A])(f: Throwable => OptionT[F, A]): OptionT[F, A] =
      delegate.handleErrorWith(fa)(f)

    def raiseError[A](e: Throwable): OptionT[F, A] =
      delegate.raiseError(e)

    def flatMap[A, B](fa: OptionT[F, A])(f: A => OptionT[F, B]): OptionT[F, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => OptionT[F, Either[A, B]]): OptionT[F, B] =
      delegate.tailRecM(a)(f)

    def suspend[A](hint: Type)(thunk: => A): OptionT[F, A] =
      OptionT.liftF(F.suspend(hint)(thunk))
  }

  private[effect] trait EitherTSync[F[_], E]
      extends Sync[EitherT[F, E, *]]
      with Clock.EitherTClock[F, E] {
    implicit protected def F: Sync[F]
    protected def C = F

    protected def delegate: MonadError[EitherT[F, E, *], Throwable] =
      EitherT.catsDataMonadErrorFForEitherT[F, Throwable, E]

    def applicative = this

    def pure[A](a: A): EitherT[F, E, A] = delegate.pure(a)

    def handleErrorWith[A](fa: EitherT[F, E, A])(
        f: Throwable => EitherT[F, E, A]): EitherT[F, E, A] =
      delegate.handleErrorWith(fa)(f)

    def raiseError[A](e: Throwable): EitherT[F, E, A] =
      delegate.raiseError(e)

    def flatMap[A, B](fa: EitherT[F, E, A])(f: A => EitherT[F, E, B]): EitherT[F, E, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => EitherT[F, E, Either[A, B]]): EitherT[F, E, B] =
      delegate.tailRecM(a)(f)

    def suspend[A](hint: Type)(thunk: => A): EitherT[F, E, A] =
      EitherT.liftF(F.suspend(hint)(thunk))
  }

  private[effect] trait StateTSync[F[_], S]
      extends Sync[StateT[F, S, *]]
      with Clock.StateTClock[F, S] {
    implicit protected def F: Sync[F]
    protected def C = F

    protected def delegate: MonadError[StateT[F, S, *], Throwable] =
      IndexedStateT.catsDataMonadErrorForIndexedStateT[F, S, Throwable]

    def applicative = this

    def pure[A](a: A): StateT[F, S, A] = delegate.pure(a)

    def handleErrorWith[A](fa: StateT[F, S, A])(
        f: Throwable => StateT[F, S, A]): StateT[F, S, A] =
      delegate.handleErrorWith(fa)(f)

    def raiseError[A](e: Throwable): StateT[F, S, A] =
      delegate.raiseError(e)

    def flatMap[A, B](fa: StateT[F, S, A])(f: A => StateT[F, S, B]): StateT[F, S, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => StateT[F, S, Either[A, B]]): StateT[F, S, B] =
      delegate.tailRecM(a)(f)

    def suspend[A](hint: Type)(thunk: => A): StateT[F, S, A] =
      StateT.liftF(F.suspend(hint)(thunk))
  }

  private[effect] trait WriterTSync[F[_], S]
      extends Sync[WriterT[F, S, *]]
      with Clock.WriterTClock[F, S] {
    implicit protected def F: Sync[F]
    protected def C = F

    protected def delegate: MonadError[WriterT[F, S, *], Throwable] =
      WriterT.catsDataMonadErrorForWriterT[F, S, Throwable]

    def applicative = this

    def pure[A](a: A): WriterT[F, S, A] = delegate.pure(a)

    def handleErrorWith[A](fa: WriterT[F, S, A])(
        f: Throwable => WriterT[F, S, A]): WriterT[F, S, A] =
      delegate.handleErrorWith(fa)(f)

    def raiseError[A](e: Throwable): WriterT[F, S, A] =
      delegate.raiseError(e)

    def flatMap[A, B](fa: WriterT[F, S, A])(f: A => WriterT[F, S, B]): WriterT[F, S, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => WriterT[F, S, Either[A, B]]): WriterT[F, S, B] =
      delegate.tailRecM(a)(f)

    def suspend[A](hint: Type)(thunk: => A): WriterT[F, S, A] =
      WriterT.liftF(F.suspend(hint)(thunk))
  }

  private[effect] trait IorTSync[F[_], L]
      extends Sync[IorT[F, L, *]]
      with Clock.IorTClock[F, L] {
    implicit protected def F: Sync[F]
    protected def C = F

    protected def delegate: MonadError[IorT[F, L, *], Throwable] =
      IorT.catsDataMonadErrorFForIorT[F, L, Throwable]

    def applicative = this

    def pure[A](a: A): IorT[F, L, A] = delegate.pure(a)

    def handleErrorWith[A](fa: IorT[F, L, A])(f: Throwable => IorT[F, L, A]): IorT[F, L, A] =
      delegate.handleErrorWith(fa)(f)

    def raiseError[A](e: Throwable): IorT[F, L, A] =
      delegate.raiseError(e)

    def flatMap[A, B](fa: IorT[F, L, A])(f: A => IorT[F, L, B]): IorT[F, L, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => IorT[F, L, Either[A, B]]): IorT[F, L, B] =
      delegate.tailRecM(a)(f)

    def suspend[A](hint: Type)(thunk: => A): IorT[F, L, A] =
      IorT.liftF(F.suspend(hint)(thunk))
  }

  private[effect] trait KleisliSync[F[_], R]
      extends Sync[Kleisli[F, R, *]]
      with Clock.KleisliClock[F, R] {
    implicit protected def F: Sync[F]
    protected def C = F

    protected def delegate: MonadError[Kleisli[F, R, *], Throwable] =
      Kleisli.catsDataMonadErrorForKleisli[F, R, Throwable]

    def applicative = this

    def pure[A](a: A): Kleisli[F, R, A] = delegate.pure(a)

    def handleErrorWith[A](fa: Kleisli[F, R, A])(
        f: Throwable => Kleisli[F, R, A]): Kleisli[F, R, A] =
      delegate.handleErrorWith(fa)(f)

    def raiseError[A](e: Throwable): Kleisli[F, R, A] =
      delegate.raiseError(e)

    def flatMap[A, B](fa: Kleisli[F, R, A])(f: A => Kleisli[F, R, B]): Kleisli[F, R, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => Kleisli[F, R, Either[A, B]]): Kleisli[F, R, B] =
      delegate.tailRecM(a)(f)

    def suspend[A](hint: Type)(thunk: => A): Kleisli[F, R, A] =
      Kleisli.liftF(F.suspend(hint)(thunk))
  }

  private[effect] trait ReaderWriterStateTSync[F[_], R, L, S]
      extends Sync[ReaderWriterStateT[F, R, L, S, *]]
      with Clock.ReaderWriterStateTClock[F, R, L, S] {
    implicit protected def F: Sync[F]
    protected def C = F

    protected def delegate: MonadError[ReaderWriterStateT[F, R, L, S, *], Throwable] =
      IndexedReaderWriterStateT.catsDataMonadErrorForIRWST[F, R, L, S, Throwable]

    def applicative = this

    def pure[A](a: A): ReaderWriterStateT[F, R, L, S, A] = delegate.pure(a)

    def handleErrorWith[A](fa: ReaderWriterStateT[F, R, L, S, A])(
        f: Throwable => ReaderWriterStateT[F, R, L, S, A]): ReaderWriterStateT[F, R, L, S, A] =
      delegate.handleErrorWith(fa)(f)

    def raiseError[A](e: Throwable): ReaderWriterStateT[F, R, L, S, A] =
      delegate.raiseError(e)

    def flatMap[A, B](fa: ReaderWriterStateT[F, R, L, S, A])(
        f: A => ReaderWriterStateT[F, R, L, S, B]): ReaderWriterStateT[F, R, L, S, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => ReaderWriterStateT[F, R, L, S, Either[A, B]])
        : ReaderWriterStateT[F, R, L, S, B] =
      delegate.tailRecM(a)(f)

    def suspend[A](hint: Type)(thunk: => A): ReaderWriterStateT[F, R, L, S, A] =
      ReaderWriterStateT.liftF(F.suspend(hint)(thunk))
  }

  sealed trait Type extends Product with Serializable

  object Type {
    case object Delay extends Type
    case object Blocking extends Type
    case object InterruptibleOnce extends Type
    case object InterruptibleMany extends Type
  }
}
