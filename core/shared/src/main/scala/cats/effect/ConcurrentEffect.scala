/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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
import cats.data.{EitherT, WriterT}
import scala.annotation.implicitNotFound
import scala.util.Either

/**
 * Type class describing effect data types that are cancelable.
 *
 * In addition to the algebras of [[Concurrent]] and of
 * [[Effect]], instances must also implement a
 * [[ConcurrentEffect!.runCancelable runCancelable]] operation that
 * triggers the evaluation, suspended in the `IO` context, but that
 * also returns a token that can be used for canceling the running
 * computation.
 *
 * Note this is the safe and generic version of [[IO.unsafeRunCancelable]].
 */
@typeclass
@implicitNotFound("""Cannot find implicit value for ConcurrentEffect[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler, a ContextShift[${F}]
or some equivalent type.""")
trait ConcurrentEffect[F[_]] extends Concurrent[F] with Effect[F] {

  /**
   * Evaluates `F[_]` with the ability to cancel it.
   *
   * The returned `SyncIO[CancelToken[F]]` is a suspended cancelable
   * action that can be used to cancel the running computation.
   *
   * [[CancelToken]] is nothing more than an alias for `F[Unit]`
   * and needs to be evaluated in order for cancelation of the
   * active process to occur.
   *
   * Contract:
   *
   *  - the evaluation of the suspended [[CancelToken]] must be asynchronous
   */
  def runCancelable[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[CancelToken[F]]

  override def toIO[A](fa: F[A]): IO[A] =
    ConcurrentEffect.toIOFromRunCancelable(fa)(this)
}

object ConcurrentEffect {

  /**
   * [[ConcurrentEffect.toIO]] default implementation, derived from
   * [[ConcurrentEffect.runCancelable]].
   */
  def toIOFromRunCancelable[F[_], A](fa: F[A])(implicit F: ConcurrentEffect[F]): IO[A] =
    IO.cancelable { cb =>
      F.toIO(F.runCancelable(fa)(r => IO(cb(r))).unsafeRunSync())
    }

  /**
   * [[ConcurrentEffect]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `ConcurrentEffect`.
   */
  implicit def catsEitherTConcurrentEffect[F[_]: ConcurrentEffect]: ConcurrentEffect[EitherT[F, Throwable, *]] =
    new EitherTConcurrentEffect[F] { def F = ConcurrentEffect[F] }

  /**
   * [[ConcurrentEffect]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `ConcurrentEffect`.
   */
  implicit def catsWriterTConcurrentEffect[F[_]: ConcurrentEffect, L: Monoid]: ConcurrentEffect[WriterT[F, L, *]] =
    new WriterTConcurrentEffect[F, L] { def F = ConcurrentEffect[F]; def L = Monoid[L] }

  private[effect] trait EitherTConcurrentEffect[F[_]]
      extends ConcurrentEffect[EitherT[F, Throwable, *]]
      with Concurrent.EitherTConcurrent[F, Throwable]
      with Effect.EitherTEffect[F] {
    protected def F: ConcurrentEffect[F]

    override def runCancelable[A](
      fa: EitherT[F, Throwable, A]
    )(cb: Either[Throwable, A] => IO[Unit]): SyncIO[CancelToken[EitherT[F, Throwable, *]]] =
      F.runCancelable(fa.value)(cb.compose(_.flatMap(x => x))).map(EitherT.liftF(_)(F))
  }

  private[effect] trait WriterTConcurrentEffect[F[_], L]
      extends ConcurrentEffect[WriterT[F, L, *]]
      with Concurrent.WriterTConcurrent[F, L]
      with Effect.WriterTEffect[F, L] {
    protected def F: ConcurrentEffect[F]
    protected def L: Monoid[L]

    override def runCancelable[A](
      fa: WriterT[F, L, A]
    )(cb: Either[Throwable, A] => IO[Unit]): SyncIO[CancelToken[WriterT[F, L, *]]] =
      F.runCancelable(fa.run)(cb.compose(_.map(_._2))).map(WriterT.liftF(_)(L, F))
  }
}
