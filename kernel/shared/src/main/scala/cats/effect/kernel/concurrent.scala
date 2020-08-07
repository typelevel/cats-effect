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

import cats.{~>, MonadError}
import cats.data.{EitherT, Ior, IorT, Kleisli, OptionT, WriterT}
import cats.{Monoid, Semigroup}
import cats.syntax.all._

trait Fiber[F[_], E, A] {
  def cancel: F[Unit]
  def join: F[Outcome[F, E, A]]

  def joinAndEmbed(onCancel: F[A])(implicit F: Concurrent[F, E]): F[A] =
    join.flatMap(_.fold(onCancel, F.raiseError(_), fa => fa))

  def joinAndEmbedNever(implicit F: Concurrent[F, E]): F[A] =
    joinAndEmbed(F.canceled *> F.never)
}

trait Concurrent[F[_], E] extends MonadError[F, E] {

  // analogous to productR, except discarding short-circuiting (and optionally some effect contexts) except for cancelation
  def forceR[A, B](fa: F[A])(fb: F[B]): F[B]

  def start[A](fa: F[A]): F[Fiber[F, E, A]]

  def uncancelable[A](body: (F ~> F) => F[A]): F[A]

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

  def bracketFull[A, B](acquire: (F ~> F) => F[A])(use: A => F[B])(
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

object Concurrent {
  def apply[F[_], E](implicit F: Concurrent[F, E]): F.type = F
  def apply[F[_]](implicit F: Concurrent[F, _], d: DummyImplicit): F.type = F

  implicit def concurrentForOptionT[F[_], E](
      implicit F0: Concurrent[F, E]): Concurrent[OptionT[F, *], E] =
    new OptionTConcurrent[F, E] {

      override implicit protected def F: Concurrent[F, E] = F0
    }

  implicit def concurrentForEitherT[F[_], E0, E](
      implicit F0: Concurrent[F, E]): Concurrent[EitherT[F, E0, *], E] =
    new EitherTConcurrent[F, E0, E] {

      override implicit protected def F: Concurrent[F, E] = F0
    }

  implicit def concurrentForKleisli[F[_], R, E](
      implicit F0: Concurrent[F, E]): Concurrent[Kleisli[F, R, *], E] =
    new KleisliConcurrent[F, R, E] {

      override implicit protected def F: Concurrent[F, E] = F0
    }

  implicit def concurrentForIorT[F[_], L, E](
      implicit F0: Concurrent[F, E],
      L0: Semigroup[L]): Concurrent[IorT[F, L, *], E] =
    new IorTConcurrent[F, L, E] {

      override implicit protected def F: Concurrent[F, E] = F0

      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def concurrentForWriterT[F[_], L, E](
      implicit F0: Concurrent[F, E],
      L0: Monoid[L]): Concurrent[WriterT[F, L, *], E] =
    new WriterTConcurrent[F, L, E] {

      override implicit protected def F: Concurrent[F, E] = F0

      override implicit protected def L: Monoid[L] = L0
    }

  private[kernel] trait OptionTConcurrent[F[_], E] extends Concurrent[OptionT[F, *], E] {

    implicit protected def F: Concurrent[F, E]

    val delegate = OptionT.catsDataMonadErrorForOptionT[F, E]

    def start[A](fa: OptionT[F, A]): OptionT[F, Fiber[OptionT[F, *], E, A]] =
      OptionT.liftF(F.start(fa.value).map(liftFiber))

    def uncancelable[A](
        body: (OptionT[F, *] ~> OptionT[F, *]) => OptionT[F, A]): OptionT[F, A] =
      OptionT(
        F.uncancelable { nat =>
          val natT: OptionT[F, *] ~> OptionT[F, *] = new ~>[OptionT[F, *], OptionT[F, *]] {
            def apply[B](optfa: OptionT[F, B]): OptionT[F, B] = OptionT(nat(optfa.value))
          }
          body(natT).value
        }
      )

    def canceled: OptionT[F, Unit] = OptionT.liftF(F.canceled)

    def onCancel[A](fa: OptionT[F, A], fin: OptionT[F, Unit]): OptionT[F, A] =
      OptionT(F.onCancel(fa.value, fin.value.void))

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

  private[kernel] trait EitherTConcurrent[F[_], E0, E]
      extends Concurrent[EitherT[F, E0, *], E] {

    implicit protected def F: Concurrent[F, E]

    val delegate = EitherT.catsDataMonadErrorFForEitherT[F, E, E0]

    def start[A](fa: EitherT[F, E0, A]): EitherT[F, E0, Fiber[EitherT[F, E0, *], E, A]] =
      EitherT.liftF(F.start(fa.value).map(liftFiber))

    def uncancelable[A](body: (EitherT[F, E0, *] ~> EitherT[F, E0, *]) => EitherT[F, E0, A])
        : EitherT[F, E0, A] =
      EitherT(
        F.uncancelable { nat =>
          val natT: EitherT[F, E0, *] ~> EitherT[F, E0, *] =
            new ~>[EitherT[F, E0, *], EitherT[F, E0, *]] {
              def apply[B](optfa: EitherT[F, E0, B]): EitherT[F, E0, B] =
                EitherT(nat(optfa.value))
            }
          body(natT).value
        }
      )

    def canceled: EitherT[F, E0, Unit] = EitherT.liftF(F.canceled)

    def onCancel[A](fa: EitherT[F, E0, A], fin: EitherT[F, E0, Unit]): EitherT[F, E0, A] =
      EitherT(F.onCancel(fa.value, fin.value.void))

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

  private[kernel] trait IorTConcurrent[F[_], L, E] extends Concurrent[IorT[F, L, *], E] {

    implicit protected def F: Concurrent[F, E]

    implicit protected def L: Semigroup[L]

    val delegate = IorT.catsDataMonadErrorFForIorT[F, L, E]

    def start[A](fa: IorT[F, L, A]): IorT[F, L, Fiber[IorT[F, L, *], E, A]] =
      IorT.liftF(F.start(fa.value).map(liftFiber))

    def uncancelable[A](
        body: (IorT[F, L, *] ~> IorT[F, L, *]) => IorT[F, L, A]): IorT[F, L, A] =
      IorT(
        F.uncancelable { nat =>
          val natT: IorT[F, L, *] ~> IorT[F, L, *] = new ~>[IorT[F, L, *], IorT[F, L, *]] {
            def apply[B](optfa: IorT[F, L, B]): IorT[F, L, B] = IorT(nat(optfa.value))
          }
          body(natT).value
        }
      )

    def canceled: IorT[F, L, Unit] = IorT.liftF(F.canceled)

    def onCancel[A](fa: IorT[F, L, A], fin: IorT[F, L, Unit]): IorT[F, L, A] =
      IorT(F.onCancel(fa.value, fin.value.void))

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

  private[kernel] trait KleisliConcurrent[F[_], R, E] extends Concurrent[Kleisli[F, R, *], E] {

    implicit protected def F: Concurrent[F, E]

    val delegate = Kleisli.catsDataMonadErrorForKleisli[F, R, E]

    def start[A](fa: Kleisli[F, R, A]): Kleisli[F, R, Fiber[Kleisli[F, R, *], E, A]] =
      Kleisli { r => (F.start(fa.run(r)).map(liftFiber)) }

    def uncancelable[A](
        body: (Kleisli[F, R, *] ~> Kleisli[F, R, *]) => Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli { r =>
        F.uncancelable { nat =>
          val natT: Kleisli[F, R, *] ~> Kleisli[F, R, *] =
            new ~>[Kleisli[F, R, *], Kleisli[F, R, *]] {
              def apply[B](stfa: Kleisli[F, R, B]): Kleisli[F, R, B] =
                Kleisli { r => nat(stfa.run(r)) }
            }
          body(natT).run(r)
        }
      }

    def canceled: Kleisli[F, R, Unit] = Kleisli.liftF(F.canceled)

    def onCancel[A](fa: Kleisli[F, R, A], fin: Kleisli[F, R, Unit]): Kleisli[F, R, A] =
      Kleisli { r => F.onCancel(fa.run(r), fin.run(r)) }

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

  private[kernel] trait WriterTConcurrent[F[_], L, E] extends Concurrent[WriterT[F, L, *], E] {

    implicit protected def F: Concurrent[F, E]

    implicit protected def L: Monoid[L]

    val delegate = WriterT.catsDataMonadErrorForWriterT[F, L, E]

    def start[A](fa: WriterT[F, L, A]): WriterT[F, L, Fiber[WriterT[F, L, *], E, A]] =
      WriterT.liftF(F.start(fa.run).map(liftFiber))

    def uncancelable[A](
        body: (WriterT[F, L, *] ~> WriterT[F, L, *]) => WriterT[F, L, A]): WriterT[F, L, A] =
      WriterT(
        F.uncancelable { nat =>
          val natT: WriterT[F, L, *] ~> WriterT[F, L, *] =
            new ~>[WriterT[F, L, *], WriterT[F, L, *]] {
              def apply[B](optfa: WriterT[F, L, B]): WriterT[F, L, B] = WriterT(nat(optfa.run))
            }
          body(natT).run
        }
      )

    def canceled: WriterT[F, L, Unit] = WriterT.liftF(F.canceled)

    //Note that this does not preserve the log from the finalizer
    def onCancel[A](fa: WriterT[F, L, A], fin: WriterT[F, L, Unit]): WriterT[F, L, A] =
      WriterT(F.onCancel(fa.run, fin.value.void))

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
