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

import cats.data.{EitherT, StateT, WriterT}

import scala.annotation.implicitNotFound
import scala.concurrent.ExecutionContext
import scala.util.Either

/**
 * A monad that can suspend side effects into the `F` context and
 * that supports lazy and potentially asynchronous evaluation.
 */
@typeclass
@implicitNotFound("""Cannot find implicit value for Effect[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Strategy or some equivalent type.""")
trait Effect[F[_]] extends Async[F] with LiftIO[F] {

  def runAsync[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit]

  /**
   * @see [[IO#shift]]
   */
  def shift[A](fa: F[A])(implicit ec: ExecutionContext): F[A] = {
    val self = flatMap(attempt(fa)) { e =>
      async { (cb: Either[Throwable, A] => Unit) =>
        ec.execute(new Runnable {
          def run() = cb(e)
        })
      }
    }

    async { cb =>
      ec.execute(new Runnable {
        def run() = runAsync(self)(e => IO { cb(e) }).unsafeRunSync()
      })
    }
  }
}

private[effect] trait EffectInstances {

  implicit def catsEitherTEffect[F[_]: Effect]: Effect[EitherT[F, Throwable, ?]] =
    new Effect[EitherT[F, Throwable, ?]] with Async.EitherTAsync[F, Throwable] with LiftIO.EitherTLiftIO[F, Throwable] {
      protected def F = Effect[F]
      protected def FF = Effect[F]

      def runAsync[A](fa: EitherT[F, Throwable, A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
        F.runAsync(fa.value)(cb.compose(_.right.flatMap(x => x)))
    }

  implicit def catsStateTEffect[F[_]: Effect, S: Monoid]: Effect[StateT[F, S, ?]] =
    new Effect[StateT[F, S, ?]] with Async.StateTAsync[F, S] with LiftIO.StateTLiftIO[F, S] {
      protected def F = Effect[F]
      protected def FA = Effect[F]

      def runAsync[A](fa: StateT[F, S, A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
        F.runAsync(fa.runA(Monoid[S].empty))(cb)
    }

  implicit def catsWriterTEffect[F[_]: Effect, L: Monoid]: Effect[WriterT[F, L, ?]] =
    new Effect[WriterT[F, L, ?]] with Async.WriterTAsync[F, L] with LiftIO.WriterTLiftIO[F, L] {
      protected def F = Effect[F]
      protected def FA = Effect[F]
      protected def L = Monoid[L]

      def runAsync[A](fa: WriterT[F, L, A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
        F.runAsync(fa.run)(cb.compose(_.right.map(_._2)))
    }
}

object Effect extends EffectInstances
