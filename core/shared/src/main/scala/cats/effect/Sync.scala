/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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
import cats.data._
import cats.syntax.all._

/**
 * A monad that can suspend the execution of side effects
 * in the `F[_]` context.
 */
@typeclass
trait Sync[F[_]] extends Bracket[F, Throwable] {
  /**
   * Suspends the evaluation of an `F` reference.
   *
   * Equivalent to `FlatMap.flatten` for pure expressions,
   * the purpose of this function is to suspend side effects
   * in `F`.
   */
  def suspend[A](thunk: => F[A]): F[A]

  /**
   * Lifts any by-name parameter into the `F` context.
   *
   * Equivalent to `Applicative.pure` for pure expressions,
   * the purpose of this function is to suspend side effects
   * in `F`.
   */
  def delay[A](thunk: => A): F[A] = suspend(pure(thunk))
}

object Sync {

  /**
   * [[Sync]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `Sync`.
   */
  implicit def catsEitherTSync[F[_]: Sync, L]: Sync[EitherT[F, L, ?]] =
    new EitherTSync[F, L] { def F = Sync[F] }

  /**
   * [[Sync]] instance built for `cats.data.OptionT` values initialized
   * with any `F` data type that also implements `Sync`.
   */
  implicit def catsOptionTSync[F[_]: Sync]: Sync[OptionT[F, ?]] =
    new OptionTSync[F] { def F = Sync[F] }

  /**
   * [[Sync]] instance built for `cats.data.StateT` values initialized
   * with any `F` data type that also implements `Sync`.
   */
  implicit def catsStateTSync[F[_]: Sync, S]: Sync[StateT[F, S, ?]] =
    new StateTSync[F, S] { def F = Sync[F] }

  /**
   * [[Sync]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `Sync`.
   */
  implicit def catsWriterTSync[F[_]: Sync, L: Monoid]: Sync[WriterT[F, L, ?]] =
    new WriterTSync[F, L] { def F = Sync[F]; def L = Monoid[L] }

  /**
   * [[Sync]] instance built for `cats.data.Kleisli` values initialized
   * with any `F` data type that also implements `Sync`.
   */
  implicit def catsKleisliSync[F[_]: Sync, R]: Sync[Kleisli[F, R, ?]] =
    new KleisliSync[F, R] { def F = Sync[F] }

  private[effect] trait EitherTSync[F[_], L] extends Sync[EitherT[F, L, ?]] {
    protected implicit def F: Sync[F]

    def pure[A](x: A): EitherT[F, L, A] =
      EitherT.pure(x)

    def handleErrorWith[A](fa: EitherT[F, L, A])(f: Throwable => EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT(F.handleErrorWith(fa.value)(f.andThen(_.value)))

    def raiseError[A](e: Throwable): EitherT[F, L, A] =
      EitherT.liftF(F.raiseError(e))

    def bracketCase[A, B](acquire: EitherT[F, L, A])
      (use: A => EitherT[F, L, B])
      (release: (A, ExitCase[Throwable]) => EitherT[F, L, Unit]): EitherT[F, L, B] = {

      EitherT(F.bracketCase(acquire.value) {
        case Right(a) => use(a).value
        case e @ Left(_) => F.pure(e.rightCast[B])
      } { (ea, br) =>
        ea match {
          case Right(a) =>
            release(a, br).value.map(_ => ())
          case Left(_) =>
            F.unit // nothing to release
        }
      })
    }

    def flatMap[A, B](fa: EitherT[F, L, A])(f: A => EitherT[F, L, B]): EitherT[F, L, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => EitherT[F, L, Either[A, B]]): EitherT[F, L, B] =
      EitherT.catsDataMonadErrorForEitherT[F, L].tailRecM(a)(f)

    def suspend[A](thunk: => EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT(F.suspend(thunk.value))
  }

  private[effect] trait OptionTSync[F[_]] extends Sync[OptionT[F, ?]]
    with Bracket.OptionTBracket[F, Throwable] {
    protected implicit def F: Sync[F]

    def suspend[A](thunk: => OptionT[F, A]): OptionT[F, A] =
      OptionT(F.suspend(thunk.value))
  }

  private[effect] trait StateTSync[F[_], S] extends Sync[StateT[F, S, ?]]
    with Bracket.StateTBracket[F, S, Throwable] {
    protected implicit def F: Sync[F]

    def suspend[A](thunk: => StateT[F, S, A]): StateT[F, S, A] =
      StateT.applyF(F.suspend(thunk.runF))
  }

  private[effect] trait WriterTSync[F[_], L] extends Sync[WriterT[F, L, ?]] {
    protected implicit def F: Sync[F]
    protected implicit def L: Monoid[L]

    def pure[A](x: A): WriterT[F, L, A] = WriterT.value(x)

    def handleErrorWith[A](fa: WriterT[F, L, A])(f: Throwable => WriterT[F, L, A]): WriterT[F, L, A] =
      WriterT.catsDataMonadErrorForWriterT[F, L, Throwable].handleErrorWith(fa)(f)

    def raiseError[A](e: Throwable): WriterT[F, L, A] =
      WriterT.catsDataMonadErrorForWriterT[F, L, Throwable].raiseError(e)

    def bracketCase[A, B](acquire: WriterT[F, L, A])
      (use: A => WriterT[F, L, B])
      (release: (A, ExitCase[Throwable]) => WriterT[F, L, Unit]): WriterT[F, L, B] = {

      acquire.flatMap { a =>
        WriterT(
          F.bracketCase(F.pure(a))(use.andThen(_.run)){ (a, res) =>
            release(a, res).value
          }
        )
      }
    }

    def flatMap[A, B](fa: WriterT[F, L, A])(f: A => WriterT[F, L, B]): WriterT[F, L, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => WriterT[F, L, Either[A, B]]): WriterT[F, L, B] =
      WriterT.catsDataMonadForWriterT[F, L].tailRecM(a)(f)

    def suspend[A](thunk: => WriterT[F, L, A]): WriterT[F, L, A] =
      WriterT(F.suspend(thunk.run))
  }

  private[effect] abstract class KleisliSync[F[_], R]
    extends Bracket.KleisliBracket[F, R, Throwable]
    with Sync[Kleisli[F, R, ?]] {

    protected implicit override def F: Sync[F]

    override def handleErrorWith[A](fa: Kleisli[F, R, A])(f: Throwable => Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli { r => F.suspend(F.handleErrorWith(fa.run(r))(e => f(e).run(r))) }

    override def flatMap[A, B](fa: Kleisli[F, R, A])(f: A => Kleisli[F, R, B]): Kleisli[F, R, B] =
      Kleisli { r => F.suspend(fa.run(r).flatMap(f.andThen(_.run(r)))) }

    def suspend[A](thunk: => Kleisli[F, R, A]): Kleisli[F, R, A] =
      Kleisli(r => F.suspend(thunk.run(r)))
  }
}
