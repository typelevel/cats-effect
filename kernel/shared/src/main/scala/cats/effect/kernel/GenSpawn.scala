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

import cats.~>
import cats.data.{EitherT, Ior, IorT, Kleisli, OptionT, WriterT}
import cats.{Monoid, Semigroup}
import cats.syntax.all._

trait GenSpawn[F[_], E] extends MonadCancel[F, E] {

  def start[A](fa: F[A]): F[Fiber[F, E, A]]

  // produces an effect which never returns
  def never[A]: F[A]

  // introduces a fairness boundary by yielding control to the underlying dispatcher
  def cede: F[Unit]

  def racePair[A, B](fa: F[A], fb: F[B])
      : F[Either[(Outcome[F, E, A], Fiber[F, E, B]), (Fiber[F, E, A], Outcome[F, E, B])]]

  def raceOutcome[A, B](fa: F[A], fb: F[B]): F[Either[Outcome[F, E, A], Outcome[F, E, B]]] =
    uncancelable { _ =>
      flatMap(racePair(fa, fb)) {
        case Left((oc, f)) => as(f.cancel, Left(oc))
        case Right((f, oc)) => as(f.cancel, Right(oc))
      }
    }

  def race[A, B](fa: F[A], fb: F[B]): F[Either[A, B]] =
    uncancelable { poll =>
      flatMap(racePair(fa, fb)) {
        case Left((oc, f)) =>
          oc match {
            case Outcome.Completed(fa) => productR(f.cancel)(map(fa)(Left(_)))
            case Outcome.Errored(ea) => productR(f.cancel)(raiseError(ea))
            case Outcome.Canceled() =>
              flatMap(onCancel(poll(f.join), f.cancel)) {
                case Outcome.Completed(fb) => map(fb)(Right(_))
                case Outcome.Errored(eb) => raiseError(eb)
                case Outcome.Canceled() => productR(canceled)(never)
              }
          }
        case Right((f, oc)) =>
          oc match {
            case Outcome.Completed(fb) => productR(f.cancel)(map(fb)(Right(_)))
            case Outcome.Errored(eb) => productR(f.cancel)(raiseError(eb))
            case Outcome.Canceled() =>
              flatMap(onCancel(poll(f.join), f.cancel)) {
                case Outcome.Completed(fa) => map(fa)(Left(_))
                case Outcome.Errored(ea) => raiseError(ea)
                case Outcome.Canceled() => productR(canceled)(never)
              }
          }
      }
    }

  def bothOutcome[A, B](fa: F[A], fb: F[B]): F[(Outcome[F, E, A], Outcome[F, E, B])] =
    uncancelable { poll =>
      flatMap(racePair(fa, fb)) {
        case Left((oc, f)) => map(onCancel(poll(f.join), f.cancel))((oc, _))
        case Right((f, oc)) => map(onCancel(poll(f.join), f.cancel))((_, oc))
      }
    }

  def both[A, B](fa: F[A], fb: F[B]): F[(A, B)] =
    uncancelable { poll =>
      flatMap(racePair(fa, fb)) {
        case Left((oc, f)) =>
          oc match {
            case Outcome.Completed(fa) =>
              flatMap(onCancel(poll(f.join), f.cancel)) {
                case Outcome.Completed(fb) => product(fa, fb)
                case Outcome.Errored(eb) => raiseError(eb)
                case Outcome.Canceled() => productR(canceled)(never)
              }
            case Outcome.Errored(ea) => productR(f.cancel)(raiseError(ea))
            case Outcome.Canceled() => productR(f.cancel)(productR(canceled)(never))
          }
        case Right((f, oc)) =>
          oc match {
            case Outcome.Completed(fb) =>
              flatMap(onCancel(poll(f.join), f.cancel)) {
                case Outcome.Completed(fa) => product(fa, fb)
                case Outcome.Errored(ea) => raiseError(ea)
                case Outcome.Canceled() => productR(canceled)(never)
              }
            case Outcome.Errored(eb) => productR(f.cancel)(raiseError(eb))
            case Outcome.Canceled() => productR(f.cancel)(productR(canceled)(never))
          }
      }
    }
}

object GenSpawn {
  import MonadCancel.{
    EitherTMonadCancel,
    IorTMonadCancel,
    KleisliMonadCancel,
    OptionTMonadCancel,
    WriterTMonadCancel
  }

  def apply[F[_], E](implicit F: GenSpawn[F, E]): F.type = F
  def apply[F[_]](implicit F: GenSpawn[F, _], d: DummyImplicit): F.type = F

  implicit def spawnForOptionT[F[_], E](
      implicit F0: GenSpawn[F, E]): GenSpawn[OptionT[F, *], E] =
    new OptionTSpawn[F, E] {

      override implicit protected def F: GenSpawn[F, E] = F0
    }

  implicit def spawnForEitherT[F[_], E0, E](
      implicit F0: GenSpawn[F, E]): GenSpawn[EitherT[F, E0, *], E] =
    new EitherTSpawn[F, E0, E] {

      override implicit protected def F: GenSpawn[F, E] = F0
    }

  implicit def spawnForKleisli[F[_], R, E](
      implicit F0: GenSpawn[F, E]): GenSpawn[Kleisli[F, R, *], E] =
    new KleisliSpawn[F, R, E] {

      override implicit protected def F: GenSpawn[F, E] = F0
    }

  implicit def spawnForIorT[F[_], L, E](
      implicit F0: GenSpawn[F, E],
      L0: Semigroup[L]): GenSpawn[IorT[F, L, *], E] =
    new IorTSpawn[F, L, E] {

      override implicit protected def F: GenSpawn[F, E] = F0

      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def spawnForWriterT[F[_], L, E](
      implicit F0: GenSpawn[F, E],
      L0: Monoid[L]): GenSpawn[WriterT[F, L, *], E] =
    new WriterTSpawn[F, L, E] {

      override implicit protected def F: GenSpawn[F, E] = F0

      override implicit protected def L: Monoid[L] = L0
    }

  private[kernel] trait OptionTSpawn[F[_], E]
      extends GenSpawn[OptionT[F, *], E]
      with OptionTMonadCancel[F, E] {

    implicit protected def F: GenSpawn[F, E]

    def start[A](fa: OptionT[F, A]): OptionT[F, Fiber[OptionT[F, *], E, A]] =
      OptionT.liftF(F.start(fa.value).map(liftFiber))

    def never[A]: OptionT[F, A] = OptionT.liftF(F.never)

    def cede: OptionT[F, Unit] = OptionT.liftF(F.cede)

    def racePair[A, B](fa: OptionT[F, A], fb: OptionT[F, B]): OptionT[
      F,
      Either[
        (Outcome[OptionT[F, *], E, A], Fiber[OptionT[F, *], E, B]),
        (Fiber[OptionT[F, *], E, A], Outcome[OptionT[F, *], E, B])]] = {
      OptionT.liftF(F.racePair(fa.value, fb.value).map {
        case Left((oc, fib)) => Left((liftOutcome(oc), liftFiber(fib)))
        case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
      })
    }

    def liftOutcome[A](oc: Outcome[F, E, Option[A]]): Outcome[OptionT[F, *], E, A] =
      oc match {
        case Outcome.Canceled() => Outcome.Canceled()
        case Outcome.Errored(e) => Outcome.Errored(e)
        case Outcome.Completed(foa) => Outcome.Completed(OptionT(foa))
      }

    def liftFiber[A](fib: Fiber[F, E, Option[A]]): Fiber[OptionT[F, *], E, A] =
      new Fiber[OptionT[F, *], E, A] {
        def cancel: OptionT[F, Unit] = OptionT.liftF(fib.cancel)
        def join: OptionT[F, Outcome[OptionT[F, *], E, A]] =
          OptionT.liftF(fib.join.map(liftOutcome))
      }
  }

  private[kernel] trait EitherTSpawn[F[_], E0, E]
      extends GenSpawn[EitherT[F, E0, *], E]
      with EitherTMonadCancel[F, E0, E] {

    implicit protected def F: GenSpawn[F, E]

    def start[A](fa: EitherT[F, E0, A]): EitherT[F, E0, Fiber[EitherT[F, E0, *], E, A]] =
      EitherT.liftF(F.start(fa.value).map(liftFiber))

    def never[A]: EitherT[F, E0, A] = EitherT.liftF(F.never)

    def cede: EitherT[F, E0, Unit] = EitherT.liftF(F.cede)

    def racePair[A, B](fa: EitherT[F, E0, A], fb: EitherT[F, E0, B]): EitherT[
      F,
      E0,
      Either[
        (Outcome[EitherT[F, E0, *], E, A], Fiber[EitherT[F, E0, *], E, B]),
        (Fiber[EitherT[F, E0, *], E, A], Outcome[EitherT[F, E0, *], E, B])]] = {
      EitherT.liftF(F.racePair(fa.value, fb.value).map {
        case Left((oc, fib)) => Left((liftOutcome(oc), liftFiber(fib)))
        case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
      })
    }

    def liftOutcome[A](oc: Outcome[F, E, Either[E0, A]]): Outcome[EitherT[F, E0, *], E, A] =
      oc match {
        case Outcome.Canceled() => Outcome.Canceled()
        case Outcome.Errored(e) => Outcome.Errored(e)
        case Outcome.Completed(foa) => Outcome.Completed(EitherT(foa))
      }

    def liftFiber[A](fib: Fiber[F, E, Either[E0, A]]): Fiber[EitherT[F, E0, *], E, A] =
      new Fiber[EitherT[F, E0, *], E, A] {
        def cancel: EitherT[F, E0, Unit] = EitherT.liftF(fib.cancel)
        def join: EitherT[F, E0, Outcome[EitherT[F, E0, *], E, A]] =
          EitherT.liftF(fib.join.map(liftOutcome))
      }
  }

  private[kernel] trait IorTSpawn[F[_], L, E]
      extends GenSpawn[IorT[F, L, *], E]
      with IorTMonadCancel[F, L, E] {

    implicit protected def F: GenSpawn[F, E]

    implicit protected def L: Semigroup[L]

    def start[A](fa: IorT[F, L, A]): IorT[F, L, Fiber[IorT[F, L, *], E, A]] =
      IorT.liftF(F.start(fa.value).map(liftFiber))

    def never[A]: IorT[F, L, A] = IorT.liftF(F.never)

    def cede: IorT[F, L, Unit] = IorT.liftF(F.cede)

    def racePair[A, B](fa: IorT[F, L, A], fb: IorT[F, L, B]): IorT[
      F,
      L,
      Either[
        (Outcome[IorT[F, L, *], E, A], Fiber[IorT[F, L, *], E, B]),
        (Fiber[IorT[F, L, *], E, A], Outcome[IorT[F, L, *], E, B])]] = {
      IorT.liftF(F.racePair(fa.value, fb.value).map {
        case Left((oc, fib)) => Left((liftOutcome(oc), liftFiber(fib)))
        case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
      })
    }

    def liftOutcome[A](oc: Outcome[F, E, Ior[L, A]]): Outcome[IorT[F, L, *], E, A] =
      oc match {
        case Outcome.Canceled() => Outcome.Canceled()
        case Outcome.Errored(e) => Outcome.Errored(e)
        case Outcome.Completed(foa) => Outcome.Completed(IorT(foa))
      }

    def liftFiber[A](fib: Fiber[F, E, Ior[L, A]]): Fiber[IorT[F, L, *], E, A] =
      new Fiber[IorT[F, L, *], E, A] {
        def cancel: IorT[F, L, Unit] = IorT.liftF(fib.cancel)
        def join: IorT[F, L, Outcome[IorT[F, L, *], E, A]] =
          IorT.liftF(fib.join.map(liftOutcome))
      }
  }

  private[kernel] trait KleisliSpawn[F[_], R, E]
      extends GenSpawn[Kleisli[F, R, *], E]
      with KleisliMonadCancel[F, R, E] {

    implicit protected def F: GenSpawn[F, E]

    def start[A](fa: Kleisli[F, R, A]): Kleisli[F, R, Fiber[Kleisli[F, R, *], E, A]] =
      Kleisli { r => (F.start(fa.run(r)).map(liftFiber)) }

    def never[A]: Kleisli[F, R, A] = Kleisli.liftF(F.never)

    def cede: Kleisli[F, R, Unit] = Kleisli.liftF(F.cede)

    def racePair[A, B](fa: Kleisli[F, R, A], fb: Kleisli[F, R, B]): Kleisli[
      F,
      R,
      Either[
        (Outcome[Kleisli[F, R, *], E, A], Fiber[Kleisli[F, R, *], E, B]),
        (Fiber[Kleisli[F, R, *], E, A], Outcome[Kleisli[F, R, *], E, B])]] = {
      Kleisli { r =>
        (F.racePair(fa.run(r), fb.run(r)).map {
          case Left((oc, fib)) => Left((liftOutcome(oc), liftFiber(fib)))
          case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
        })
      }
    }

    def liftOutcome[A](oc: Outcome[F, E, A]): Outcome[Kleisli[F, R, *], E, A] = {

      val nat: F ~> Kleisli[F, R, *] = new ~>[F, Kleisli[F, R, *]] {
        def apply[B](fa: F[B]) = Kleisli.liftF(fa)
      }

      oc.mapK(nat)
    }

    def liftFiber[A](fib: Fiber[F, E, A]): Fiber[Kleisli[F, R, *], E, A] =
      new Fiber[Kleisli[F, R, *], E, A] {
        def cancel: Kleisli[F, R, Unit] = Kleisli.liftF(fib.cancel)
        def join: Kleisli[F, R, Outcome[Kleisli[F, R, *], E, A]] =
          Kleisli.liftF(fib.join.map(liftOutcome))
      }
  }

  private[kernel] trait WriterTSpawn[F[_], L, E]
      extends GenSpawn[WriterT[F, L, *], E]
      with WriterTMonadCancel[F, L, E] {

    implicit protected def F: GenSpawn[F, E]

    implicit protected def L: Monoid[L]

    def start[A](fa: WriterT[F, L, A]): WriterT[F, L, Fiber[WriterT[F, L, *], E, A]] =
      WriterT.liftF(F.start(fa.run).map(liftFiber))

    def never[A]: WriterT[F, L, A] = WriterT.liftF(F.never)

    def cede: WriterT[F, L, Unit] = WriterT.liftF(F.cede)

    def racePair[A, B](fa: WriterT[F, L, A], fb: WriterT[F, L, B]): WriterT[
      F,
      L,
      Either[
        (Outcome[WriterT[F, L, *], E, A], Fiber[WriterT[F, L, *], E, B]),
        (Fiber[WriterT[F, L, *], E, A], Outcome[WriterT[F, L, *], E, B])]] = {
      WriterT.liftF(F.racePair(fa.run, fb.run).map {
        case Left((oc, fib)) => Left((liftOutcome(oc), liftFiber(fib)))
        case Right((fib, oc)) => Right((liftFiber(fib), liftOutcome(oc)))
      })
    }

    def liftOutcome[A](oc: Outcome[F, E, (L, A)]): Outcome[WriterT[F, L, *], E, A] =
      oc match {
        case Outcome.Canceled() => Outcome.Canceled()
        case Outcome.Errored(e) => Outcome.Errored(e)
        case Outcome.Completed(foa) => Outcome.Completed(WriterT(foa))
      }

    def liftFiber[A](fib: Fiber[F, E, (L, A)]): Fiber[WriterT[F, L, *], E, A] =
      new Fiber[WriterT[F, L, *], E, A] {
        def cancel: WriterT[F, L, Unit] = WriterT.liftF(fib.cancel)
        def join: WriterT[F, L, Outcome[WriterT[F, L, *], E, A]] =
          WriterT.liftF(fib.join.map(liftOutcome))
      }
  }
}
