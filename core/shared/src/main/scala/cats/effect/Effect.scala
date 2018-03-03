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
import cats.data.{EitherT, StateT, WriterT}
import scala.annotation.implicitNotFound
import scala.util.Either

/**
 * A monad that can suspend side effects into the `F` context and
 * that supports lazy and potentially asynchronous evaluation.
 */
@typeclass
@implicitNotFound("""Cannot find implicit value for Effect[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Strategy or some equivalent type.""")
trait Effect[F[_]] extends AsyncStart[F] {
  /**
   * Evaluates `F[_]`, with the effect of starting the run-loop
   * being suspended in the `IO` context.
   *
   * Note that evaluating the returned `IO[Unit]` is guaranteed
   * to execute immediately:
   * {{{
   *   val io = F.runAsync(fa)(cb)
   *
   *   // For triggering actual execution, guaranteed to be
   *   // immediate because it doesn't wait for the result
   *   io.unsafeRunSync
   * }}}
   */
  def runAsync[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit]

  /**
   * Evaluates `F[_]` with the ability to cancel it.
   *
   * The returned `IO[IO[Unit]]` is a suspended cancelable action that
   * can be used to cancel the running computation.
   *
   * Note that evaluating the returned `IO` value, along with
   * the boxed cancellable action are guaranteed to have immediate
   * (synchronous) execution so you can safely do this, even
   * on top of JavaScript (which has no ability to block threads):
   *
   * {{{
   *   val io = F.runCancelable(fa)(cb)
   *
   *   // For triggering asynchronous execution
   *   val cancel = io.unsafeRunSync
   *   // For cancellation
   *   cancel.unsafeRunSync
   * }}}
   */
  def runCancelable[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]]
}

object Effect {
  /**
   * [[Effect]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `Effect`.
   *
   * Note this `Effect` instance has been specialized for `Throwable`,
   * because otherwise there's no way to implement the
   * [[Effect.runAsync runAsync]] operation due to its signature,
   * as we couldn't represent `L` values when pulling that value
   * out of its `EitherT[F, L, ?]` context.
   *
   * This is in contrast with the `EitherT` instances defined for
   * [[Async]] or for [[Sync]], which do not require this
   * specialization.
   */
  implicit def catsEitherTEffect[F[_]: Effect]: Effect[EitherT[F, Throwable, ?]] =
    new EitherTEffect[F] { def F = Effect[F] }

  /**
   * [[Effect]] instance built for `cats.data.StateT` values initialized
   * with any `F` data type that also implements `Effect`.
   */
  implicit def catsStateTEffect[F[_]: Effect, S: Monoid]: Effect[StateT[F, S, ?]] =
    new StateTEffect[F, S] { def F = Effect[F]; def S = Monoid[S] }

  /**
   * [[Effect]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `Effect`.
   */
  implicit def catsWriterTEffect[F[_]: Effect, L: Monoid]: Effect[WriterT[F, L, ?]] =
    new WriterTEffect[F, L] { def F = Effect[F]; def L = Monoid[L] }

  private[effect] trait EitherTEffect[F[_]] extends Effect[EitherT[F, Throwable, ?]]
    with AsyncStart.EitherTAsyncStart[F, Throwable] {

    protected def F: Effect[F]

    def runAsync[A](fa: EitherT[F, Throwable, A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
      F.runAsync(fa.value)(cb.compose(_.right.flatMap(x => x)))
    def runCancelable[A](fa: EitherT[F, Throwable, A])(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]] =
      F.runCancelable(fa.value)(cb.compose(_.right.flatMap(x => x)))
  }

  private[effect] trait StateTEffect[F[_], S] extends Effect[StateT[F, S, ?]]
    with AsyncStart.StateTAsyncStart[F, S] {

    protected def F: Effect[F]
    protected def S: Monoid[S]

    def runAsync[A](fa: StateT[F, S, A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
      F.runAsync(fa.runA(S.empty)(F))(cb)
    def runCancelable[A](fa: StateT[F, S, A])(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]] =
      F.runCancelable(fa.runA(S.empty)(F))(cb)
  }

  private[effect] trait WriterTEffect[F[_], L] extends Effect[WriterT[F, L, ?]]
    with AsyncStart.WriterTAsyncStart[F, L] {

    protected def F: Effect[F]
    protected def L: Monoid[L]

    def runAsync[A](fa: WriterT[F, L, A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
      F.runAsync(fa.run)(cb.compose(_.right.map(_._2)))
    def runCancelable[A](fa: WriterT[F, L, A])(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]] =
      F.runCancelable(fa.run)(cb.compose(_.right.map(_._2)))
  }
}
