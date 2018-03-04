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
 * Type class describing effect data types that are cancelable.
 *
 * In addition to the algebras of [[CancelableAsync]] and of
 * [[Effect]], instances must also implement a
 * [[CancelableEffect!.runCancelable runCancelable]] operation that
 * triggers the evaluation, suspended in the `IO` context, but that
 * also returns a token that can be used for cancelling the running
 * computation.
 *
 * Note this is the safe and generic version of [[IO.unsafeRunCancelable]].
 */
@typeclass
@implicitNotFound("""Cannot find implicit value for CancelableEffect[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait CancelableEffect[F[_]] extends CancelableAsync[F] with Effect[F] {
  /**
   * Evaluates `F[_]` with the ability to cancel it.
   *
   * The returned `IO[IO[Unit]]` is a suspended cancelable action that
   * can be used to cancel the running computation.
   *
   * Note that evaluating the returned `IO` value, along with
   * the boxed cancelable action are guaranteed to have immediate
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

object CancelableEffect {
  /**
   * [[CancelableEffect]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `CancelableEffect`.
   */
  implicit def catsEitherTCEffect[F[_]: CancelableEffect]: CancelableEffect[EitherT[F, Throwable, ?]] =
    new EitherTCEffect[F] { def F = CancelableEffect[F] }

  /**
   * [[CancelableEffect]] instance built for `cats.data.StateT` values initialized
   * with any `F` data type that also implements `CancelableEffect`.
   */
  implicit def catsStateTCEffect[F[_]: CancelableEffect, S: Monoid]: CancelableEffect[StateT[F, S, ?]] =
    new StateTCEffect[F, S] { def F = CancelableEffect[F]; def S = Monoid[S] }

  /**
   * [[CancelableEffect]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `CancelableEffect`.
   */
  implicit def catsWriterTCEffect[F[_]: CancelableEffect, L: Monoid]: CancelableEffect[WriterT[F, L, ?]] =
    new WriterTCEffect[F, L] { def F = CancelableEffect[F]; def L = Monoid[L] }

  private[effect] trait EitherTCEffect[F[_]] extends CancelableEffect[EitherT[F, Throwable, ?]]
    with CancelableAsync.EitherTCAsync[F, Throwable]
    with Effect.EitherTEffect[F] {

    protected def F: CancelableEffect[F]

    def runCancelable[A](fa: EitherT[F, Throwable, A])(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]] =
      F.runCancelable(fa.value)(cb.compose(_.right.flatMap(x => x)))
  }

  private[effect] trait StateTCEffect[F[_], S] extends CancelableEffect[StateT[F, S, ?]]
    with CancelableAsync.StateTCAsync[F, S]
    with Effect.StateTEffect[F, S] {

    protected def F: CancelableEffect[F]
    protected def S: Monoid[S]

    def runCancelable[A](fa: StateT[F, S, A])(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]] =
      F.runCancelable(fa.runA(S.empty)(F))(cb)
  }

  private[effect] trait WriterTCEffect[F[_], L] extends CancelableEffect[WriterT[F, L, ?]]
    with CancelableAsync.WriterTCAsync[F, L]
    with Effect.WriterTEffect[F, L] {

    protected def F: CancelableEffect[F]
    protected def L: Monoid[L]

    def runCancelable[A](fa: WriterT[F, L, A])(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]] =
      F.runCancelable(fa.run)(cb.compose(_.right.map(_._2)))
  }
}
