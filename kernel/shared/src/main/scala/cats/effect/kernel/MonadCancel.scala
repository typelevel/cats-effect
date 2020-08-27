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

import cats.{MonadError, Monoid, Semigroup}
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
import cats.syntax.all._

trait MonadCancel[F[_], E] extends MonadError[F, E] {

  // analogous to productR, except discarding short-circuiting (and optionally some effect contexts) except for cancelation
  def forceR[A, B](fa: F[A])(fb: F[B]): F[B]

  def uncancelable[A](body: Poll[F] => F[A]): F[A]

  // produces an effect which is already canceled (and doesn't introduce an async boundary)
  // this is effectively a way for a fiber to commit suicide and yield back to its parent
  // The fallback (unit) value is produced if the effect is sequenced into a block in which
  // cancelation is suppressed.
  def canceled: F[Unit]

  def onCancel[A](fa: F[A], fin: F[Unit]): F[A]

  def guarantee[A](fa: F[A], fin: F[Unit]): F[A] =
    guaranteeCase(fa)(_ => fin)

  def guaranteeCase[A](fa: F[A])(fin: Outcome[F, E, A] => F[Unit]): F[A] =
    bracketCase(unit)(_ => fa)((_, oc) => fin(oc))

  def bracket[A, B](acquire: F[A])(use: A => F[B])(release: A => F[Unit]): F[B] =
    bracketCase(acquire)(use)((a, _) => release(a))

  def bracketCase[A, B](acquire: F[A])(use: A => F[B])(
      release: (A, Outcome[F, E, B]) => F[Unit]): F[B] =
    bracketFull(_ => acquire)(use)(release)

  def bracketFull[A, B](acquire: Poll[F] => F[A])(use: A => F[B])(
      release: (A, Outcome[F, E, B]) => F[Unit]): F[B] =
    uncancelable { poll =>
      flatMap(acquire(poll)) { a =>
        val finalized = onCancel(poll(use(a)), release(a, Outcome.Canceled()))
        val handled = onError(finalized) {
          case e => void(attempt(release(a, Outcome.Errored(e))))
        }
        flatMap(handled)(b => as(attempt(release(a, Outcome.Completed(pure(b)))), b))
      }
    }
}

object MonadCancel {

  def apply[F[_], E](implicit F: MonadCancel[F, E]): F.type = F
  def apply[F[_]](implicit F: MonadCancel[F, _], d: DummyImplicit): F.type = F

  implicit def monadCancelForOptionT[F[_], E](
      implicit F0: MonadCancel[F, E]): MonadCancel[OptionT[F, *], E] =
    new OptionTMonadCancel[F, E] {

      override implicit protected def F: MonadCancel[F, E] = F0
    }

  implicit def monadCancelForEitherT[F[_], E0, E](
      implicit F0: MonadCancel[F, E]): MonadCancel[EitherT[F, E0, *], E] =
    new EitherTMonadCancel[F, E0, E] {

      override implicit protected def F: MonadCancel[F, E] = F0
    }

  implicit def monadCancelForKleisli[F[_], R, E](
      implicit F0: MonadCancel[F, E]): MonadCancel[Kleisli[F, R, *], E] =
    new KleisliMonadCancel[F, R, E] {

      override implicit protected def F: MonadCancel[F, E] = F0
    }

  implicit def monadCancelForIorT[F[_], L, E](
      implicit F0: MonadCancel[F, E],
      L0: Semigroup[L]): MonadCancel[IorT[F, L, *], E] =
    new IorTMonadCancel[F, L, E] {

      override implicit protected def F: MonadCancel[F, E] = F0

      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def monadCancelForWriterT[F[_], L, E](
      implicit F0: MonadCancel[F, E],
      L0: Monoid[L]): MonadCancel[WriterT[F, L, *], E] =
    new WriterTMonadCancel[F, L, E] {

      override implicit protected def F: MonadCancel[F, E] = F0

      override implicit protected def L: Monoid[L] = L0
    }

  implicit def monadCancelForStateT[F[_], S, E](
      implicit F0: MonadCancel[F, E]): MonadCancel[StateT[F, S, *], E] =
    new StateTMonadCancel[F, S, E] {
      override implicit protected def F = F0
    }

  implicit def monadCancelForReaderWriterStateT[F[_], E0, L, S, E](
      implicit F0: MonadCancel[F, E],
      L0: Monoid[L]): MonadCancel[ReaderWriterStateT[F, E0, L, S, *], E] =
    new ReaderWriterStateTMonadCancel[F, E0, L, S, E] {
      override implicit protected def F = F0
      override implicit protected def L = L0
    }

  private[kernel] trait OptionTMonadCancel[F[_], E] extends MonadCancel[OptionT[F, *], E] {
    implicit protected def F: MonadCancel[F, E]

    protected def delegate: MonadError[OptionT[F, *], E] =
      OptionT.catsDataMonadErrorForOptionT[F, E]

    def uncancelable[A](body: Poll[OptionT[F, *]] => OptionT[F, A]): OptionT[F, A] =
      OptionT(
        F.uncancelable { nat =>
          val natT = new Poll[OptionT[F, *]] {
            def apply[B](optfa: OptionT[F, B]): OptionT[F, B] = OptionT(nat(optfa.value))
          }
          body(natT).value
        }
      )

    def canceled: OptionT[F, Unit] = OptionT.liftF(F.canceled)

    def onCancel[A](fa: OptionT[F, A], fin: OptionT[F, Unit]): OptionT[F, A] =
      OptionT(F.onCancel(fa.value, fin.value.void))

    def forceR[A, B](fa: OptionT[F, A])(fb: OptionT[F, B]): OptionT[F, B] =
      OptionT(
        F.forceR(fa.value)(fb.value)
      )

    def pure[A](a: A): OptionT[F, A] = delegate.pure(a)

    def raiseError[A](e: E): OptionT[F, A] = delegate.raiseError(e)

    def handleErrorWith[A](fa: OptionT[F, A])(f: E => OptionT[F, A]): OptionT[F, A] =
      delegate.handleErrorWith(fa)(f)

    def flatMap[A, B](fa: OptionT[F, A])(f: A => OptionT[F, B]): OptionT[F, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => OptionT[F, Either[A, B]]): OptionT[F, B] =
      delegate.tailRecM(a)(f)
  }

  private[kernel] trait EitherTMonadCancel[F[_], E0, E]
      extends MonadCancel[EitherT[F, E0, *], E] {

    implicit protected def F: MonadCancel[F, E]

    protected def delegate: MonadError[EitherT[F, E0, *], E] =
      EitherT.catsDataMonadErrorFForEitherT[F, E, E0]

    def uncancelable[A](body: Poll[EitherT[F, E0, *]] => EitherT[F, E0, A]): EitherT[F, E0, A] =
      EitherT(
        F.uncancelable { nat =>
          val natT =
            new Poll[EitherT[F, E0, *]] {
              def apply[B](optfa: EitherT[F, E0, B]): EitherT[F, E0, B] =
                EitherT(nat(optfa.value))
            }
          body(natT).value
        }
      )

    def canceled: EitherT[F, E0, Unit] = EitherT.liftF(F.canceled)

    def onCancel[A](fa: EitherT[F, E0, A], fin: EitherT[F, E0, Unit]): EitherT[F, E0, A] =
      EitherT(F.onCancel(fa.value, fin.value.void))

    def forceR[A, B](fa: EitherT[F, E0, A])(fb: EitherT[F, E0, B]): EitherT[F, E0, B] =
      EitherT(
        F.forceR(fa.value)(fb.value)
      )

    def pure[A](a: A): EitherT[F, E0, A] = delegate.pure(a)

    def raiseError[A](e: E): EitherT[F, E0, A] = delegate.raiseError(e)

    def handleErrorWith[A](fa: EitherT[F, E0, A])(
        f: E => EitherT[F, E0, A]): EitherT[F, E0, A] =
      delegate.handleErrorWith(fa)(f)

    def flatMap[A, B](fa: EitherT[F, E0, A])(f: A => EitherT[F, E0, B]): EitherT[F, E0, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => EitherT[F, E0, Either[A, B]]): EitherT[F, E0, B] =
      delegate.tailRecM(a)(f)
  }

  private[kernel] trait IorTMonadCancel[F[_], L, E] extends MonadCancel[IorT[F, L, *], E] {

    implicit protected def F: MonadCancel[F, E]

    implicit protected def L: Semigroup[L]

    protected def delegate: MonadError[IorT[F, L, *], E] =
      IorT.catsDataMonadErrorFForIorT[F, L, E]

    def uncancelable[A](body: Poll[IorT[F, L, *]] => IorT[F, L, A]): IorT[F, L, A] =
      IorT(
        F.uncancelable { nat =>
          val natT = new Poll[IorT[F, L, *]] {
            def apply[B](optfa: IorT[F, L, B]): IorT[F, L, B] = IorT(nat(optfa.value))
          }
          body(natT).value
        }
      )

    def canceled: IorT[F, L, Unit] = IorT.liftF(F.canceled)

    def onCancel[A](fa: IorT[F, L, A], fin: IorT[F, L, Unit]): IorT[F, L, A] =
      IorT(F.onCancel(fa.value, fin.value.void))

    def forceR[A, B](fa: IorT[F, L, A])(fb: IorT[F, L, B]): IorT[F, L, B] =
      IorT(
        F.forceR(fa.value)(fb.value)
      )

    def pure[A](a: A): IorT[F, L, A] = delegate.pure(a)

    def raiseError[A](e: E): IorT[F, L, A] = delegate.raiseError(e)

    def handleErrorWith[A](fa: IorT[F, L, A])(f: E => IorT[F, L, A]): IorT[F, L, A] =
      delegate.handleErrorWith(fa)(f)

    def flatMap[A, B](fa: IorT[F, L, A])(f: A => IorT[F, L, B]): IorT[F, L, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => IorT[F, L, Either[A, B]]): IorT[F, L, B] =
      delegate.tailRecM(a)(f)
  }

  private[kernel] trait KleisliMonadCancel[F[_], R, E]
      extends MonadCancel[Kleisli[F, R, *], E] {

    implicit protected def F: MonadCancel[F, E]

    protected def delegate: MonadError[Kleisli[F, R, *], E] =
      Kleisli.catsDataMonadErrorForKleisli[F, R, E]

    def uncancelable[A](body: Poll[Kleisli[F, R, *]] => Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli { r =>
        F.uncancelable { nat =>
          val natT =
            new Poll[Kleisli[F, R, *]] {
              def apply[B](stfa: Kleisli[F, R, B]): Kleisli[F, R, B] =
                Kleisli { r => nat(stfa.run(r)) }
            }
          body(natT).run(r)
        }
      }

    def canceled: Kleisli[F, R, Unit] = Kleisli.liftF(F.canceled)

    def onCancel[A](fa: Kleisli[F, R, A], fin: Kleisli[F, R, Unit]): Kleisli[F, R, A] =
      Kleisli { r => F.onCancel(fa.run(r), fin.run(r)) }

    def forceR[A, B](fa: Kleisli[F, R, A])(fb: Kleisli[F, R, B]): Kleisli[F, R, B] =
      Kleisli(r => F.forceR(fa.run(r))(fb.run(r)))

    def pure[A](a: A): Kleisli[F, R, A] = delegate.pure(a)

    def raiseError[A](e: E): Kleisli[F, R, A] = delegate.raiseError(e)

    def handleErrorWith[A](fa: Kleisli[F, R, A])(f: E => Kleisli[F, R, A]): Kleisli[F, R, A] =
      delegate.handleErrorWith(fa)(f)

    def flatMap[A, B](fa: Kleisli[F, R, A])(f: A => Kleisli[F, R, B]): Kleisli[F, R, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => Kleisli[F, R, Either[A, B]]): Kleisli[F, R, B] =
      delegate.tailRecM(a)(f)
  }

  private[kernel] trait WriterTMonadCancel[F[_], L, E]
      extends MonadCancel[WriterT[F, L, *], E] {

    implicit protected def F: MonadCancel[F, E]

    implicit protected def L: Monoid[L]

    protected def delegate: MonadError[WriterT[F, L, *], E] =
      WriterT.catsDataMonadErrorForWriterT[F, L, E]

    def uncancelable[A](body: Poll[WriterT[F, L, *]] => WriterT[F, L, A]): WriterT[F, L, A] =
      WriterT(
        F.uncancelable { nat =>
          val natT =
            new Poll[WriterT[F, L, *]] {
              def apply[B](optfa: WriterT[F, L, B]): WriterT[F, L, B] = WriterT(nat(optfa.run))
            }
          body(natT).run
        }
      )

    def canceled: WriterT[F, L, Unit] = WriterT.liftF(F.canceled)

    //Note that this does not preserve the log from the finalizer
    def onCancel[A](fa: WriterT[F, L, A], fin: WriterT[F, L, Unit]): WriterT[F, L, A] =
      WriterT(F.onCancel(fa.run, fin.value.void))

    def forceR[A, B](fa: WriterT[F, L, A])(fb: WriterT[F, L, B]): WriterT[F, L, B] =
      WriterT(
        F.forceR(fa.run)(fb.run)
      )

    def pure[A](a: A): WriterT[F, L, A] = delegate.pure(a)

    def raiseError[A](e: E): WriterT[F, L, A] = delegate.raiseError(e)

    def handleErrorWith[A](fa: WriterT[F, L, A])(f: E => WriterT[F, L, A]): WriterT[F, L, A] =
      delegate.handleErrorWith(fa)(f)

    def flatMap[A, B](fa: WriterT[F, L, A])(f: A => WriterT[F, L, B]): WriterT[F, L, B] =
      delegate.flatMap(fa)(f)

    def tailRecM[A, B](a: A)(f: A => WriterT[F, L, Either[A, B]]): WriterT[F, L, B] =
      delegate.tailRecM(a)(f)
  }

  private[kernel] trait StateTMonadCancel[F[_], S, E] extends MonadCancel[StateT[F, S, *], E] {

    implicit protected def F: MonadCancel[F, E]

    protected def delegate: MonadError[StateT[F, S, *], E] =
      IndexedStateT.catsDataMonadErrorForIndexedStateT[F, S, E]

    def pure[A](x: A): StateT[F, S, A] =
      delegate.pure(x)

    def handleErrorWith[A](fa: StateT[F, S, A])(f: E => StateT[F, S, A]): StateT[F, S, A] =
      delegate.handleErrorWith(fa)(f)

    def raiseError[A](e: E): StateT[F, S, A] =
      delegate.raiseError(e)

    def flatMap[A, B](fa: StateT[F, S, A])(f: A => StateT[F, S, B]): StateT[F, S, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => StateT[F, S, Either[A, B]]): StateT[F, S, B] =
      delegate.tailRecM(a)(f)

    def canceled: StateT[F, S, Unit] =
      StateT.liftF(F.canceled)

    // discards state changes in fa
    def forceR[A, B](fa: StateT[F, S, A])(fb: StateT[F, S, B]): StateT[F, S, B] =
      StateT[F, S, B](s => F.forceR(fa.runA(s))(fb.run(s)))

    // discards state changes in fin, also fin cannot observe state changes in fa
    def onCancel[A](fa: StateT[F, S, A], fin: StateT[F, S, Unit]): StateT[F, S, A] =
      StateT[F, S, A](s => F.onCancel(fa.run(s), fin.runA(s)))

    def uncancelable[A](body: Poll[StateT[F, S, *]] => StateT[F, S, A]): StateT[F, S, A] =
      StateT[F, S, A] { s =>
        F uncancelable { poll =>
          val poll2 = new Poll[StateT[F, S, *]] {
            def apply[B](fb: StateT[F, S, B]) =
              StateT[F, S, B](s => poll(fb.run(s)))
          }

          body(poll2).run(s)
        }
      }
  }

  private[kernel] trait ReaderWriterStateTMonadCancel[F[_], E0, L, S, E]
      extends MonadCancel[ReaderWriterStateT[F, E0, L, S, *], E] {

    implicit protected def F: MonadCancel[F, E]

    implicit protected def L: Monoid[L]

    protected def delegate: MonadError[ReaderWriterStateT[F, E0, L, S, *], E] =
      IndexedReaderWriterStateT.catsDataMonadErrorForIRWST[F, E0, L, S, E]

    def pure[A](x: A): ReaderWriterStateT[F, E0, L, S, A] =
      delegate.pure(x)

    def handleErrorWith[A](fa: ReaderWriterStateT[F, E0, L, S, A])(
        f: E => ReaderWriterStateT[F, E0, L, S, A]): ReaderWriterStateT[F, E0, L, S, A] =
      delegate.handleErrorWith(fa)(f)

    def raiseError[A](e: E): ReaderWriterStateT[F, E0, L, S, A] =
      delegate.raiseError(e)

    def flatMap[A, B](fa: ReaderWriterStateT[F, E0, L, S, A])(
        f: A => ReaderWriterStateT[F, E0, L, S, B]): ReaderWriterStateT[F, E0, L, S, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => ReaderWriterStateT[F, E0, L, S, Either[A, B]])
        : ReaderWriterStateT[F, E0, L, S, B] =
      delegate.tailRecM(a)(f)

    def canceled: ReaderWriterStateT[F, E0, L, S, Unit] =
      ReaderWriterStateT.liftF(F.canceled)

    // discards state changes in fa
    def forceR[A, B](fa: ReaderWriterStateT[F, E0, L, S, A])(
        fb: ReaderWriterStateT[F, E0, L, S, B]): ReaderWriterStateT[F, E0, L, S, B] =
      ReaderWriterStateT[F, E0, L, S, B]((e, s) => F.forceR(fa.runA(e, s))(fb.run(e, s)))

    // discards state changes in fin, also fin cannot observe state changes in fa
    def onCancel[A](
        fa: ReaderWriterStateT[F, E0, L, S, A],
        fin: ReaderWriterStateT[F, E0, L, S, Unit]): ReaderWriterStateT[F, E0, L, S, A] =
      ReaderWriterStateT[F, E0, L, S, A]((e, s) => F.onCancel(fa.run(e, s), fin.runA(e, s)))

    def uncancelable[A](
        body: Poll[ReaderWriterStateT[F, E0, L, S, *]] => ReaderWriterStateT[F, E0, L, S, A])
        : ReaderWriterStateT[F, E0, L, S, A] =
      ReaderWriterStateT[F, E0, L, S, A] { (e, s) =>
        F uncancelable { poll =>
          val poll2 = new Poll[ReaderWriterStateT[F, E0, L, S, *]] {
            def apply[B](fb: ReaderWriterStateT[F, E0, L, S, B]) =
              ReaderWriterStateT[F, E0, L, S, B]((e, s) => poll(fb.run(e, s)))
          }

          body(poll2).run(e, s)
        }
      }
  }
}
