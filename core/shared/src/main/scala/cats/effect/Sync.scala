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

import cats.data.{EitherT, StateT}

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

private[effect] trait SyncInstances {

  implicit object catsSyncForEval extends Sync[Eval] {
    def pure[A](a: A): Eval[A] = Now(a)

    // TODO replace with delegation when we upgrade to cats 1.0
    def handleErrorWith[A](fa: Eval[A])(f: Throwable => Eval[A]): Eval[A] =
      suspend(try Now(fa.value) catch { case internals.NonFatal(t) => f(t) })

    // TODO replace with delegation when we upgrade to cats 1.0
    def raiseError[A](e: Throwable): Eval[A] = suspend(throw e)

    def flatMap[A, B](fa: Eval[A])(f: A => Eval[B]): Eval[B] = fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => Eval[Either[A,B]]): Eval[B] = {
      f(a) flatMap {
        case Left(nextA) => tailRecM(nextA)(f)
        case Right(b) => pure(b)
      }
    }

    def suspend[A](thunk: => Eval[A]): Eval[A] = Eval.defer(thunk)

    override def delay[A](thunk: => A): Eval[A] = Eval.always(thunk)
  }

  implicit def catsEitherTSync[F[_]: Sync, L]: Sync[EitherT[F, L, ?]] =
    new EitherTSync[F, L] { def F = Sync[F] }

  implicit def catsStateTSync[F[_]: Sync, S]: Sync[StateT[F, S, ?]] =
    new StateTSync[F, S] { def F = Sync[F] }

  private[effect] trait EitherTSync[F[_], L] extends Sync[EitherT[F, L, ?]] {
    protected def F: Sync[F]
    private implicit def _F = F

    def pure[A](x: A): EitherT[F, L, A] =
      EitherT.pure(x)

    def handleErrorWith[A](fa: EitherT[F, L, A])(f: Throwable => EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT(F.handleErrorWith(fa.value)(f.andThen(_.value)))

    def raiseError[A](e: Throwable): EitherT[F, L, A] =
      EitherT.liftT(F.raiseError(e))

    def flatMap[A, B](fa: EitherT[F, L, A])(f: A => EitherT[F, L, B]): EitherT[F, L, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => EitherT[F, L, Either[A, B]]): EitherT[F, L, B] =
      EitherT.catsDataMonadErrorForEitherT[F, L].tailRecM(a)(f)

    def suspend[A](thunk: => EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT(F.suspend(thunk.value))
  }

  private[effect] trait StateTSync[F[_], S] extends Sync[StateT[F, S, ?]] {
    protected def F: Sync[F]
    private implicit def _F = F

    def pure[A](x: A): StateT[F, S, A] = StateT.pure(x)

    def handleErrorWith[A](fa: StateT[F, S, A])(f: Throwable => StateT[F, S, A]): StateT[F, S, A] =
      StateT(s => F.handleErrorWith(fa.run(s))(e => f(e).run(s)))

    def raiseError[A](e: Throwable): StateT[F, S, A] =
      StateT.lift(F.raiseError(e))

    def flatMap[A, B](fa: StateT[F, S, A])(f: A => StateT[F, S, B]): StateT[F, S, B] =
      fa.flatMap(f)

    // overwriting the pre-existing one, since flatMap is guaranteed stack-safe
    def tailRecM[A, B](a: A)(f: A => StateT[F, S, Either[A, B]]): StateT[F, S, B] =
      StateT.catsDataMonadForStateT[F, S].tailRecM(a)(f)

    def suspend[A](thunk: => StateT[F, S, A]): StateT[F, S, A] =
      StateT.applyF(F.suspend(thunk.runF))
  }
}

object Sync extends SyncInstances
