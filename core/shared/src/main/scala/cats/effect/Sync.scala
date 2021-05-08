/*
 * Copyright (c) 2017-2021 The Typelevel Cats-effect Project Developers
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

import cats.data._
import cats.effect.concurrent.Ref
import cats.syntax.all._
import scala.annotation.implicitNotFound
import scala.annotation.nowarn

/**
 * A monad that can suspend the execution of side effects
 * in the `F[_]` context.
 */
@implicitNotFound("Could not find an instance of Sync for ${F}")
trait Sync[F[_]] extends BracketThrow[F] with Defer[F] {

  /**
   * Suspends the evaluation of an `F` reference.
   *
   * Equivalent to `FlatMap.flatten` for pure expressions,
   * the purpose of this function is to suspend side effects
   * in `F`.
   */
  @deprecated("use defer", "2.4.0")
  def suspend[A](thunk: => F[A]): F[A]

  /**
   * Alias for `suspend` that suspends the evaluation of
   * an `F` reference and implements `cats.Defer` typeclass.
   */
  @nowarn("cat=deprecation")
  final override def defer[A](fa: => F[A]): F[A] = suspend(fa)

  /**
   * Lifts any by-name parameter into the `F` context.
   *
   * Equivalent to `Applicative.pure` for pure expressions,
   * the purpose of this function is to suspend side effects
   * in `F`.
   */
  def delay[A](thunk: => A): F[A] = defer(pure(thunk))
}

object Sync {

  /**
   * [[Sync]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `Sync`.
   */
  implicit def catsEitherTSync[F[_]: Sync, L]: Sync[EitherT[F, L, *]] =
    new EitherTSync[F, L] { def F = Sync[F] }

  /**
   * [[Sync]] instance built for `cats.data.OptionT` values initialized
   * with any `F` data type that also implements `Sync`.
   */
  implicit def catsOptionTSync[F[_]: Sync]: Sync[OptionT[F, *]] =
    new OptionTSync[F] { def F = Sync[F] }

  /**
   * [[Sync]] instance built for `cats.data.StateT` values initialized
   * with any `F` data type that also implements `Sync`.
   */
  implicit def catsStateTSync[F[_]: Sync, S]: Sync[StateT[F, S, *]] =
    new StateTSync[F, S] { def F = Sync[F] }

  /**
   * [[Sync]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `Sync`.
   */
  implicit def catsWriterTSync[F[_]: Sync, L: Monoid]: Sync[WriterT[F, L, *]] =
    new WriterTSync[F, L] { def F = Sync[F]; def L = Monoid[L] }

  /**
   * [[Sync]] instance built for `cats.data.Kleisli` values initialized
   * with any `F` data type that also implements `Sync`.
   */
  implicit def catsKleisliSync[F[_]: Sync, R]: Sync[Kleisli[F, R, *]] =
    new KleisliSync[F, R] { def F = Sync[F] }

  /**
   * [[Sync]] instance built for `cats.data.IorT` values initialized
   * with any `F` data type that also implements `Sync`.
   */
  implicit def catsIorTSync[F[_]: Sync, L: Semigroup]: Sync[IorT[F, L, *]] =
    new IorTSync[F, L] { def F = Sync[F]; def L = Semigroup[L] }

  /**
   * [[Sync]] instance built for `cats.data.ReaderWriterStateT` values initialized
   * with any `F` data type that also implements `Sync`.
   */
  implicit def catsReaderWriteStateTSync[F[_]: Sync, E, L: Monoid, S]: Sync[ReaderWriterStateT[F, E, L, S, *]] =
    new ReaderWriterStateTSync[F, E, L, S] { def F = Sync[F]; def L = Monoid[L] }

  private[effect] trait EitherTSync[F[_], L] extends Sync[EitherT[F, L, *]] {
    implicit protected def F: Sync[F]

    def pure[A](x: A): EitherT[F, L, A] =
      EitherT.pure(x)

    def handleErrorWith[A](fa: EitherT[F, L, A])(f: Throwable => EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT(F.handleErrorWith(fa.value)(f.andThen(_.value)))

    def raiseError[A](e: Throwable): EitherT[F, L, A] =
      EitherT.liftF(F.raiseError(e))

    def bracketCase[A, B](
      acquire: EitherT[F, L, A]
    )(use: A => EitherT[F, L, B])(release: (A, ExitCase[Throwable]) => EitherT[F, L, Unit]): EitherT[F, L, B] =
      EitherT.liftF(Ref.of[F, Option[L]](None)).flatMap { ref =>
        EitherT(
          F.bracketCase(acquire.value) {
              case Right(a)    => use(a).value
              case l @ Left(_) => F.pure(l.rightCast[B])
            } {
              case (Left(_), _) => F.unit //Nothing to release
              case (Right(a), ExitCase.Completed) =>
                release(a, ExitCase.Completed).value.flatMap {
                  case Left(l)  => ref.set(Some(l))
                  case Right(_) => F.unit
                }
              case (Right(a), res) => release(a, res).value.void
            }
            .flatMap[Either[L, B]] {
              case r @ Right(_) => ref.get.map(_.fold(r: Either[L, B])(Either.left[L, B]))
              case l @ Left(_)  => F.pure(l)
            }
        )
      }

    def flatMap[A, B](fa: EitherT[F, L, A])(f: A => EitherT[F, L, B]): EitherT[F, L, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => EitherT[F, L, Either[A, B]]): EitherT[F, L, B] =
      EitherT.catsDataMonadErrorForEitherT[F, L].tailRecM(a)(f)

    def suspend[A](thunk: => EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT(F.defer(thunk.value))

    override def uncancelable[A](fa: EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT(F.uncancelable(fa.value))
  }

  private[effect] trait OptionTSync[F[_]] extends Sync[OptionT[F, *]] {
    implicit protected def F: Sync[F]

    def pure[A](x: A): OptionT[F, A] = OptionT.pure(x)

    def handleErrorWith[A](fa: OptionT[F, A])(f: Throwable => OptionT[F, A]): OptionT[F, A] =
      OptionT.catsDataMonadErrorForOptionT[F, Throwable].handleErrorWith(fa)(f)

    def raiseError[A](e: Throwable): OptionT[F, A] =
      OptionT.catsDataMonadErrorForOptionT[F, Throwable].raiseError(e)

    def bracketCase[A, B](
      acquire: OptionT[F, A]
    )(use: A => OptionT[F, B])(release: (A, ExitCase[Throwable]) => OptionT[F, Unit]): OptionT[F, B] =
      //Boolean represents if release returned None
      OptionT.liftF(Ref.of[F, Boolean](false)).flatMap { ref =>
        OptionT(
          F.bracketCase(acquire.value) {
              case Some(a) => use(a).value
              case None    => F.pure(Option.empty[B])
            } {
              case (None, _) => F.unit //Nothing to release
              case (Some(a), ExitCase.Completed) =>
                release(a, ExitCase.Completed).value.flatMap {
                  case None    => ref.set(true)
                  case Some(_) => F.unit
                }
              case (Some(a), res) => release(a, res).value.void
            }
            .flatMap[Option[B]] {
              case s @ Some(_) => ref.get.map(b => if (b) None else s)
              case None        => F.pure(None)
            }
        )
      }

    def flatMap[A, B](fa: OptionT[F, A])(f: A => OptionT[F, B]): OptionT[F, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => OptionT[F, Either[A, B]]): OptionT[F, B] =
      OptionT.catsDataMonadErrorForOptionT[F, Throwable].tailRecM(a)(f)

    def suspend[A](thunk: => OptionT[F, A]): OptionT[F, A] =
      OptionT(F.defer(thunk.value))

    override def uncancelable[A](fa: OptionT[F, A]): OptionT[F, A] =
      OptionT(F.uncancelable(fa.value))
  }

  private[effect] trait StateTSync[F[_], S] extends Sync[StateT[F, S, *]] {
    implicit protected def F: Sync[F]

    def pure[A](x: A): StateT[F, S, A] = StateT.pure(x)

    def handleErrorWith[A](fa: StateT[F, S, A])(f: Throwable => StateT[F, S, A]): StateT[F, S, A] =
      StateT(s => F.handleErrorWith(fa.run(s))(e => f(e).run(s)))

    def raiseError[A](e: Throwable): StateT[F, S, A] =
      StateT.liftF(F.raiseError(e))

    def bracketCase[A, B](
      acquire: StateT[F, S, A]
    )(use: A => StateT[F, S, B])(release: (A, ExitCase[Throwable]) => StateT[F, S, Unit]): StateT[F, S, B] =
      StateT.liftF(Ref.of[F, Option[S]](None)).flatMap { ref =>
        StateT { startS =>
          F.bracketCase[(S, A), (S, B)](acquire.run(startS)) {
              case (s, a) =>
                use(a).run(s).flatTap { case (s, _) => ref.set(Some(s)) }
            } {
              case ((oldS, a), ExitCase.Completed) =>
                ref.get
                  .map(_.getOrElse(oldS))
                  .flatMap(s => release(a, ExitCase.Completed).runS(s))
                  .flatMap(s => ref.set(Some(s)))
              case ((s, a), br) =>
                release(a, br).run(s).void
            }
            .flatMap { case (s, b) => ref.get.map(_.getOrElse(s)).tupleRight(b) }
        }
      }

    override def uncancelable[A](fa: StateT[F, S, A]): StateT[F, S, A] =
      fa.transformF(F.uncancelable)

    def flatMap[A, B](fa: StateT[F, S, A])(f: A => StateT[F, S, B]): StateT[F, S, B] =
      fa.flatMap(f)

    // overwriting the pre-existing one, since flatMap is guaranteed stack-safe
    def tailRecM[A, B](a: A)(f: A => StateT[F, S, Either[A, B]]): StateT[F, S, B] =
      IndexedStateT.catsDataMonadForIndexedStateT[F, S].tailRecM(a)(f)

    def suspend[A](thunk: => StateT[F, S, A]): StateT[F, S, A] =
      StateT.applyF(F.defer(thunk.runF))
  }

  private[effect] trait WriterTSync[F[_], L] extends Sync[WriterT[F, L, *]] {
    implicit protected def F: Sync[F]
    implicit protected def L: Monoid[L]

    def pure[A](x: A): WriterT[F, L, A] = WriterT.value(x)

    def handleErrorWith[A](fa: WriterT[F, L, A])(f: Throwable => WriterT[F, L, A]): WriterT[F, L, A] =
      WriterT.catsDataMonadErrorForWriterT[F, L, Throwable].handleErrorWith(fa)(f)

    def raiseError[A](e: Throwable): WriterT[F, L, A] =
      WriterT.catsDataMonadErrorForWriterT[F, L, Throwable].raiseError(e)

    def bracketCase[A, B](
      acquire: WriterT[F, L, A]
    )(use: A => WriterT[F, L, B])(release: (A, ExitCase[Throwable]) => WriterT[F, L, Unit]): WriterT[F, L, B] =
      WriterT(
        Ref[F].of(L.empty).flatMap { ref =>
          F.bracketCase(acquire.run) { la =>
              WriterT(la.pure[F]).flatMap(use).run
            } {
              case ((_, a), ec) =>
                val r = release(a, ec).run
                if (ec == ExitCase.Completed)
                  r.flatMap { case (l, _) => ref.set(l) }
                else
                  r.void
            }
            .flatMap { lb =>
              ref.get.map(l => lb.leftMap(_ |+| l))
            }
        }
      )

    override def uncancelable[A](fa: WriterT[F, L, A]): WriterT[F, L, A] =
      WriterT(F.uncancelable(fa.run))

    def flatMap[A, B](fa: WriterT[F, L, A])(f: A => WriterT[F, L, B]): WriterT[F, L, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => WriterT[F, L, Either[A, B]]): WriterT[F, L, B] =
      WriterT.catsDataMonadForWriterT[F, L].tailRecM(a)(f)

    def suspend[A](thunk: => WriterT[F, L, A]): WriterT[F, L, A] =
      WriterT(F.defer(thunk.run))
  }

  abstract private[effect] class KleisliSync[F[_], R]
      extends Bracket.KleisliBracket[F, R, Throwable]
      with Sync[Kleisli[F, R, *]] {
    implicit override protected def F: Sync[F]

    override def handleErrorWith[A](fa: Kleisli[F, R, A])(f: Throwable => Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli { r =>
        F.defer(F.handleErrorWith(fa.run(r))(e => f(e).run(r)))
      }

    override def flatMap[A, B](fa: Kleisli[F, R, A])(f: A => Kleisli[F, R, B]): Kleisli[F, R, B] =
      Kleisli { r =>
        F.defer(fa.run(r).flatMap(f.andThen(_.run(r))))
      }

    def suspend[A](thunk: => Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli(r => F.defer(thunk.run(r)))

    override def uncancelable[A](fa: Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli { r =>
        F.defer(F.uncancelable(fa.run(r)))
      }
  }

  private[effect] trait IorTSync[F[_], L] extends Sync[IorT[F, L, *]] {
    implicit protected def F: Sync[F]
    implicit protected def L: Semigroup[L]

    def pure[A](x: A): IorT[F, L, A] =
      IorT.pure(x)

    def handleErrorWith[A](fa: IorT[F, L, A])(f: Throwable => IorT[F, L, A]): IorT[F, L, A] =
      IorT(F.handleErrorWith(fa.value)(f.andThen(_.value)))

    def raiseError[A](e: Throwable): IorT[F, L, A] =
      IorT.liftF(F.raiseError(e))

    def bracketCase[A, B](
      acquire: IorT[F, L, A]
    )(use: A => IorT[F, L, B])(release: (A, ExitCase[Throwable]) => IorT[F, L, Unit]): IorT[F, L, B] =
      IorT.liftF(Ref[F].of(().rightIor[L])).flatMapF { ref =>
        F.bracketCase(acquire.value) { ia =>
            IorT.fromIor[F](ia).flatMap(use).value
          } { (ia, ec) =>
            ia.toOption.fold(F.unit) { a =>
              val r = release(a, ec).value
              if (ec == ExitCase.Completed)
                r.flatMap {
                  case Ior.Right(_) => F.unit
                  case other        => ref.set(other.void)
                }
              else
                r.void
            }
          }
          .flatMap {
            case l @ Ior.Left(_) => F.pure(l)
            case other           => ref.get.map(other <* _)
          }
      }

    def flatMap[A, B](fa: IorT[F, L, A])(f: A => IorT[F, L, B]): IorT[F, L, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => IorT[F, L, Either[A, B]]): IorT[F, L, B] =
      IorT.catsDataMonadErrorForIorT[F, L].tailRecM(a)(f)

    def suspend[A](thunk: => IorT[F, L, A]): IorT[F, L, A] =
      IorT(F.defer(thunk.value))

    override def uncancelable[A](fa: IorT[F, L, A]): IorT[F, L, A] =
      IorT(F.uncancelable(fa.value))
  }

  private[effect] trait ReaderWriterStateTSync[F[_], E, L, S] extends Sync[ReaderWriterStateT[F, E, L, S, *]] {
    implicit protected def F: Sync[F]
    implicit protected def L: Monoid[L]

    def pure[A](x: A): ReaderWriterStateT[F, E, L, S, A] =
      ReaderWriterStateT.pure(x)

    def handleErrorWith[A](
      fa: ReaderWriterStateT[F, E, L, S, A]
    )(f: Throwable => ReaderWriterStateT[F, E, L, S, A]): ReaderWriterStateT[F, E, L, S, A] =
      ReaderWriterStateT((e, s) => F.handleErrorWith(fa.run(e, s))(f.andThen(_.run(e, s))))

    def raiseError[A](e: Throwable): ReaderWriterStateT[F, E, L, S, A] =
      ReaderWriterStateT.liftF(F.raiseError(e))

    def bracketCase[A, B](acquire: ReaderWriterStateT[F, E, L, S, A])(
      use: A => ReaderWriterStateT[F, E, L, S, B]
    )(release: (A, ExitCase[Throwable]) => ReaderWriterStateT[F, E, L, S, Unit]): ReaderWriterStateT[F, E, L, S, B] =
      ReaderWriterStateT.liftF(Ref[F].of[(L, Option[S])]((L.empty, None))).flatMap { ref =>
        ReaderWriterStateT { (e, startS) =>
          F.bracketCase(acquire.run(e, startS)) {
              case (l, s, a) =>
                ReaderWriterStateT.pure[F, E, L, S, A](a).tell(l).flatMap(use).run(e, s).flatTap {
                  case (l, s, _) => ref.set((l, Some(s)))
                }
            } {
              case ((_, oldS, a), ExitCase.Completed) =>
                ref.get
                  .map(_._2.getOrElse(oldS))
                  .flatMap(s => release(a, ExitCase.Completed).run(e, s))
                  .flatMap { case (l, s, _) => ref.set((l, Some(s))) }
              case ((_, s, a), ec) =>
                release(a, ec).run(e, s).void
            }
            .flatMap {
              case (l, s, b) =>
                ref.get.map { case (l2, s2) => (l |+| l2, s2.getOrElse(s), b) }
            }
        }
      }

    def flatMap[A, B](
      fa: ReaderWriterStateT[F, E, L, S, A]
    )(f: A => ReaderWriterStateT[F, E, L, S, B]): ReaderWriterStateT[F, E, L, S, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => ReaderWriterStateT[F, E, L, S, Either[A, B]]): ReaderWriterStateT[F, E, L, S, B] =
      IndexedReaderWriterStateT.catsDataMonadForRWST[F, E, L, S].tailRecM(a)(f)

    def suspend[A](thunk: => ReaderWriterStateT[F, E, L, S, A]): ReaderWriterStateT[F, E, L, S, A] =
      ReaderWriterStateT((e, s) => F.defer(thunk.run(e, s)))

    override def uncancelable[A](fa: ReaderWriterStateT[F, E, L, S, A]): ReaderWriterStateT[F, E, L, S, A] =
      ReaderWriterStateT((e, s) => F.uncancelable(fa.run(e, s)))
  }

  /**
   * Summon an instance of [[Sync]] for `F`.
   */
  @inline def apply[F[_]](implicit instance: Sync[F]): Sync[F] = instance

  trait Ops[F[_], A] {
    type TypeClassType <: Sync[F]
    def self: F[A]
    val typeClassInstance: TypeClassType
  }
  trait AllOps[F[_], A] extends Ops[F, A] {
    type TypeClassType <: Sync[F]
  }
  trait ToSyncOps {
    implicit def toSyncOps[F[_], A](target: F[A])(implicit tc: Sync[F]): Ops[F, A] {
      type TypeClassType = Sync[F]
    } = new Ops[F, A] {
      type TypeClassType = Sync[F]
      val self: F[A] = target
      val typeClassInstance: TypeClassType = tc
    }
  }
  object nonInheritedOps extends ToSyncOps

  // indirection required to avoid spurious static forwarders that conflict on case-insensitive filesystems (scala-js/scala-js#4148)
  class ops$ {
    implicit def toAllSyncOps[F[_], A](target: F[A])(implicit tc: Sync[F]): AllOps[F, A] {
      type TypeClassType = Sync[F]
    } = new AllOps[F, A] {
      type TypeClassType = Sync[F]
      val self: F[A] = target
      val typeClassInstance: TypeClassType = tc
    }
  }
  // TODO this lacks a MODULE$ field; is that okay???
  val ops = new ops$
}
