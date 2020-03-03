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
 * A monad that can suspend side effects into the `F` context and
 * that supports lazy and potentially asynchronous evaluation.
 *
 * This type class is describing data types that:
 *
 *  1. implement the [[Async]] algebra
 *  1. implement a lawful [[Effect!.runAsync runAsync]] operation
 *     that triggers the evaluation (in the context of [[IO]])
 *
 * Note this is the safe and generic version of [[IO.unsafeRunAsync]]
 * (aka Haskell's `unsafePerformIO`).
 */
@typeclass
@implicitNotFound("""Cannot find implicit value for Effect[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler, a ContextShift[${F}]
or some equivalent type.""")
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
   * [[Effect.toIO]] as a natural transformation.
   */
  def toIOK[F[_]](implicit F: Effect[F]): F ~> IO = λ[F ~> IO](F.toIO(_))

  /**
   * [[Effect]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `Effect`.
   */
  implicit def catsEitherTEffect[F[_]: Effect]: Effect[EitherT[F, Throwable, *]] =
    new EitherTEffect[F] { def F = Effect[F] }

  /**
   * [[Effect]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `Effect`.
   */
  implicit def catsWriterTEffect[F[_]: Effect, L: Monoid]: Effect[WriterT[F, L, *]] =
    new WriterTEffect[F, L] { def F = Effect[F]; def L = Monoid[L] }

  private[effect] trait EitherTEffect[F[_]]
      extends Effect[EitherT[F, Throwable, *]]
      with Async.EitherTAsync[F, Throwable] {
    protected def F: Effect[F]

    def runAsync[A](fa: EitherT[F, Throwable, A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
      F.runAsync(fa.value)(cb.compose(_.flatMap(x => x)))

    override def toIO[A](fa: EitherT[F, Throwable, A]): IO[A] =
      F.toIO(F.rethrow(fa.value))
  }

  private[effect] trait WriterTEffect[F[_], L] extends Effect[WriterT[F, L, *]] with Async.WriterTAsync[F, L] {
    protected def F: Effect[F]
    protected def L: Monoid[L]

    def runAsync[A](fa: WriterT[F, L, A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
      F.runAsync(fa.run)(cb.compose(_.map(_._2)))

    override def toIO[A](fa: WriterT[F, L, A]): IO[A] =
      F.toIO(fa.value(F))
  }
}
