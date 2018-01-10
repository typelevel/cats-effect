/*
 * Copyright 2017 Typelevel
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

import cats.data.{EitherT, IndexedStateT, OptionT, StateT, WriterT}
import cats.effect.internals.NonFatal

/**
 * A monad that can suspend the execution of side effects
 * in the `F[_]` context.
 */
@typeclass
trait Sync[F[_]] extends MonadError[F, Throwable] {
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

private[effect] abstract class SyncInstances {

  implicit def catsEitherTSync[F[_]: Sync, L]: Sync[EitherT[F, L, ?]] =
    new EitherTSync[F, L] { def F = Sync[F] }

  implicit val catsEitherTEvalSync: Sync[EitherT[Eval, Throwable, ?]] =
    new Sync[EitherT[Eval, Throwable, ?]] {

      def pure[A](x: A): EitherT[Eval, Throwable, A] = EitherT.pure(x)

      def handleErrorWith[A](fa: EitherT[Eval, Throwable, A])(f: Throwable => EitherT[Eval, Throwable, A]): EitherT[Eval, Throwable, A] =
        EitherT(fa.value.flatMap(_.fold(f.andThen(_.value), a => Eval.now(Right(a)))))

      def raiseError[A](e: Throwable) =
        EitherT.left(Eval.now(e))

      def flatMap[A, B](fa: EitherT[Eval, Throwable, A])(f: A => EitherT[Eval, Throwable, B]): EitherT[Eval, Throwable, B] =
        fa.flatMap(f)

      def tailRecM[A, B](a: A)(f: A => EitherT[Eval, Throwable, Either[A, B]]): EitherT[Eval, Throwable, B] =
        EitherT.catsDataMonadErrorForEitherT[Eval, Throwable].tailRecM(a)(f)

      def suspend[A](thunk: => EitherT[Eval, Throwable, A]): EitherT[Eval, Throwable, A] = {
        EitherT {
          Eval.always(try {
            thunk.value.value
          } catch {
            case NonFatal(t) => Left(t)
          })
        }
      }
    }

  implicit def catsOptionTSync[F[_]: Sync]: Sync[OptionT[F, ?]] =
    new OptionTSync[F] { def F = Sync[F] }

  implicit def catsStateTSync[F[_]: Sync, S]: Sync[StateT[F, S, ?]] =
    new StateTSync[F, S] { def F = Sync[F] }

  implicit def catsWriterTSync[F[_]: Sync, L: Monoid]: Sync[WriterT[F, L, ?]] =
    new WriterTSync[F, L] { def F = Sync[F]; def L = Monoid[L] }

  private[effect] trait EitherTSync[F[_], L] extends Sync[EitherT[F, L, ?]] {
    protected def F: Sync[F]
    private implicit def _F = F

    def pure[A](x: A): EitherT[F, L, A] =
      EitherT.pure(x)

    def handleErrorWith[A](fa: EitherT[F, L, A])(f: Throwable => EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT(F.handleErrorWith(fa.value)(f.andThen(_.value)))

    def raiseError[A](e: Throwable): EitherT[F, L, A] =
      EitherT.liftF(F.raiseError(e))

    def flatMap[A, B](fa: EitherT[F, L, A])(f: A => EitherT[F, L, B]): EitherT[F, L, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => EitherT[F, L, Either[A, B]]): EitherT[F, L, B] =
      EitherT.catsDataMonadErrorForEitherT[F, L].tailRecM(a)(f)

    def suspend[A](thunk: => EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT(F.suspend(thunk.value))
  }


  private[effect] trait OptionTSync[F[_]] extends Sync[OptionT[F, ?]] {
    protected def F: Sync[F]
    private implicit def _F = F

    def pure[A](x: A): OptionT[F, A] = OptionT.pure(x)

    def handleErrorWith[A](fa: OptionT[F, A])(f: Throwable => OptionT[F, A]): OptionT[F, A] =
      OptionT.catsDataMonadErrorForOptionT[F, Throwable].handleErrorWith(fa)(f)

    def raiseError[A](e: Throwable): OptionT[F, A] =
      OptionT.catsDataMonadErrorForOptionT[F, Throwable].raiseError(e)

    def flatMap[A, B](fa: OptionT[F, A])(f: A => OptionT[F, B]): OptionT[F, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => OptionT[F, Either[A, B]]): OptionT[F, B] =
      OptionT.catsDataMonadForOptionT[F].tailRecM(a)(f)

    def suspend[A](thunk: => OptionT[F, A]): OptionT[F, A] =
      OptionT(F.suspend(thunk.value))
  }

  private[effect] trait StateTSync[F[_], S] extends Sync[StateT[F, S, ?]] {
    protected def F: Sync[F]
    private implicit def _F = F

    def pure[A](x: A): StateT[F, S, A] = StateT.pure(x)

    def handleErrorWith[A](fa: StateT[F, S, A])(f: Throwable => StateT[F, S, A]): StateT[F, S, A] =
      StateT(s => F.handleErrorWith(fa.run(s))(e => f(e).run(s)))

    def raiseError[A](e: Throwable): StateT[F, S, A] =
      StateT.liftF(F.raiseError(e))

    def flatMap[A, B](fa: StateT[F, S, A])(f: A => StateT[F, S, B]): StateT[F, S, B] =
      fa.flatMap(f)

    // overwriting the pre-existing one, since flatMap is guaranteed stack-safe
    def tailRecM[A, B](a: A)(f: A => StateT[F, S, Either[A, B]]): StateT[F, S, B] =
      IndexedStateT.catsDataMonadForIndexedStateT[F, S].tailRecM(a)(f)

    def suspend[A](thunk: => StateT[F, S, A]): StateT[F, S, A] =
      StateT.applyF(F.suspend(thunk.runF))
  }

  private[effect] trait WriterTSync[F[_], L] extends Sync[WriterT[F, L, ?]] {
    protected def F: Sync[F]
    private implicit def _F = F

    protected def L: Monoid[L]
    private implicit def _L = L

    def pure[A](x: A): WriterT[F, L, A] = WriterT.value(x)

    def handleErrorWith[A](fa: WriterT[F, L, A])(f: Throwable => WriterT[F, L, A]): WriterT[F, L, A] =
      WriterT.catsDataMonadErrorForWriterT[F, L, Throwable].handleErrorWith(fa)(f)

    def raiseError[A](e: Throwable): WriterT[F, L, A] =
      WriterT.catsDataMonadErrorForWriterT[F, L, Throwable].raiseError(e)

    def flatMap[A, B](fa: WriterT[F, L, A])(f: A => WriterT[F, L, B]): WriterT[F, L, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => WriterT[F, L, Either[A, B]]): WriterT[F, L, B] =
      WriterT.catsDataMonadForWriterT[F, L].tailRecM(a)(f)

    def suspend[A](thunk: => WriterT[F, L, A]): WriterT[F, L, A] =
      WriterT(F.suspend(thunk.run))
  }
}

object Sync extends SyncInstances
