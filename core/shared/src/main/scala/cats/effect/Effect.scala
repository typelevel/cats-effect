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
import cats.implicits._
import scala.annotation.implicitNotFound
import scala.util.Either

/**
 * A monad that can suspend side effects into the `F` context and
 * that supports lazy and potentially asynchronous evaluation.
 *
 * This type class is describing data types that:
 *
 *  1. implement the [[Async]] algebra
 *  1. implement a lawful [[Effect!.runAsync runAsync]] operation
 *     that triggers the evaluation (in the context of [[IO]])
 *  1. implement a lawful [[Effect!.runSyncStep runSyncStep]] operation
 *     which triggers evaluation up to the first asynchronous boundary
 *
 * Note this is the safe and generic version of [[IO.unsafeRunAsync]]
 * (aka Haskell's `unsafePerformIO`).
 */
@typeclass
@implicitNotFound("""Cannot find implicit value for Effect[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait Effect[F[_]] extends Async[F] {

  /**
   * Evaluates `F[_]`, with the effect of starting the run-loop
   * being suspended in the `SyncIO` context.
   *
   * {{{
   *   val io = F.runAsync(fa)(cb)
   *   // Running io results in evaluation of `fa` starting 
   *   io.unsafeRunSync
   * }}}
   */
  def runAsync[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit]

  /**
   * Returns a `SyncIO` which runs `fa` until it reaches an asynchronous
   * boundary.
   *
   * If it is possible to run the entirety of `fa` synchronously, its
   * result is returned wrapped in a `Right`. Otherwise, the
   * continuation (asynchronous) effect is returned in a `Left`.
   */
  def runSyncStep[A](fa: F[A]): SyncIO[Either[F[A], A]]

  /**
   * Convert to an IO[A].
   *
   * The law is that toIO(liftIO(ioa)) is the same as ioa
   */
  def toIO[A](fa: F[A]): IO[A] =
    Effect.toIOFromRunAsync(fa)(this)
}

object Effect {
  /**
   * [[Effect.toIO]] default implementation, derived from [[Effect.runAsync]].
   */
  def toIOFromRunAsync[F[_], A](f: F[A])(implicit F: Effect[F]): IO[A] =
    IO.async { cb =>
      F.runAsync(f)(r => IO(cb(r))).unsafeRunSync()
    }

  /**
   * [[Effect]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `Effect`.
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
    with Async.EitherTAsync[F, Throwable] {

    protected def F: Effect[F]

    def runAsync[A](fa: EitherT[F, Throwable, A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
      F.runAsync(fa.value)(cb.compose(_.right.flatMap(x => x)))

    def runSyncStep[A](fa: EitherT[F, Throwable, A]): SyncIO[Either[EitherT[F, Throwable, A], A]] = {
      F.runSyncStep(fa.value).flatMap {
        case Left(feta) => SyncIO.pure(Left(EitherT(feta)))
        case Right(eta) => SyncIO.fromEither(eta).map(Right(_))
      }
    }

    override def toIO[A](fa: EitherT[F, Throwable, A]): IO[A] =
      F.toIO(F.rethrow(fa.value))
  }

  private[effect] trait StateTEffect[F[_], S] extends Effect[StateT[F, S, ?]]
    with Async.StateTAsync[F, S] {

    protected def F: Effect[F]
    protected def S: Monoid[S]

    def runAsync[A](fa: StateT[F, S, A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
      F.runAsync(fa.runA(S.empty)(F))(cb)

    def runSyncStep[A](fa: StateT[F, S, A]): SyncIO[Either[StateT[F, S, A], A]] =
      F.runSyncStep(fa.runA(S.empty)(F)).map(_.leftMap(fa => StateT.liftF(fa)(F)))

    override def toIO[A](fa: StateT[F, S, A]): IO[A] =
      F.toIO(fa.runA(S.empty)(F))
  }

  private[effect] trait WriterTEffect[F[_], L] extends Effect[WriterT[F, L, ?]]
    with Async.WriterTAsync[F, L] {

    protected def F: Effect[F]
    protected def L: Monoid[L]

    def runAsync[A](fa: WriterT[F, L, A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
      F.runAsync(fa.run)(cb.compose(_.right.map(_._2)))

    def runSyncStep[A](fa: WriterT[F, L, A]): SyncIO[Either[WriterT[F, L, A], A]] = {
      F.runSyncStep(fa.run).map {
        case Left(fla) => Left(WriterT(fla))
        case Right(la) => Right(la._2)
      }
    }

    override def toIO[A](fa: WriterT[F, L, A]): IO[A] =
      F.toIO(fa.value(F))
  }
}
