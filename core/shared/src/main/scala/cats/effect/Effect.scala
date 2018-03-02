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
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait Effect[F[_]] extends Async[F] {
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
}

private[effect] abstract class EffectInstances {

  implicit def catsEitherTEffect[F[_]: Effect]: Effect[EitherT[F, Throwable, ?]] =
    new EitherTEffect[F] { def F = Effect[F] }

  implicit def catsStateTEffect[F[_]: Effect, S: Monoid]: Effect[StateT[F, S, ?]] =
    new StateTEffect[F, S] { def F = Effect[F]; def S = Monoid[S] }

  implicit def catsWriterTEffect[F[_]: Effect, L: Monoid]: Effect[WriterT[F, L, ?]] =
    new WriterTEffect[F, L] { def F = Effect[F]; def L = Monoid[L] }

  private[effect] trait EitherTEffect[F[_]] extends Effect[EitherT[F, Throwable, ?]]
    with Async.EitherTAsync[F, Throwable] {

    protected def F: Effect[F]

    def runAsync[A](fa: EitherT[F, Throwable, A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
      F.runAsync(fa.value)(cb.compose(_.right.flatMap(x => x)))
  }

  private[effect] trait StateTEffect[F[_], S] extends Effect[StateT[F, S, ?]]
    with Async.StateTAsync[F, S] {

    protected def F: Effect[F]
    protected def S: Monoid[S]

    def runAsync[A](fa: StateT[F, S, A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
      F.runAsync(fa.runA(S.empty)(F))(cb)
  }

  private[effect] trait WriterTEffect[F[_], L] extends Effect[WriterT[F, L, ?]]
    with Async.WriterTAsync[F, L] {

    protected def F: Effect[F]
    protected def L: Monoid[L]

    def runAsync[A](fa: WriterT[F, L, A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
      F.runAsync(fa.run)(cb.compose(_.right.map(_._2)))
  }
}

object Effect extends EffectInstances
