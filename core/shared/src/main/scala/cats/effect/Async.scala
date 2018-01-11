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

import cats.data.{EitherT, OptionT, StateT, WriterT}

import scala.annotation.implicitNotFound
import scala.concurrent.ExecutionContext
import scala.util.{Either, Right}

/**
 * A monad that can describe asynchronous or synchronous computations that
 * produce exactly one result.
 */
@typeclass
@implicitNotFound("""Cannot find implicit value for Async[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Strategy or some equivalent type.""")
trait Async[F[_]] extends Sync[F] with LiftIO[F] {

  /**
   * Creates an `F[A]` instance from a provided function
   * that will have a callback injected for signaling the
   * final result of an asynchronous process.
   *
   * @param k is a function that should be called with a
   *       callback for signaling the result once it is ready
   */
  def async[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]

  /**
   * @see [[IO#shift]]
   */
  def shift(implicit ec: ExecutionContext): F[Unit] = {
    async { (cb: Either[Throwable, Unit] => Unit) =>
      ec.execute(new Runnable {
        def run() = cb(Right(()))
      })
    }
  }

  override def liftIO[A](ioa: IO[A]): F[A] = {
    // Able to provide default with `IO#to`, given this `Async[F]`
    ioa.to[F](this)
  }
}

private[effect] abstract class AsyncInstances {

  implicit def catsEitherTAsync[F[_]: Async, L]: Async[EitherT[F, L, ?]] =
    new EitherTAsync[F, L] { def F = Async[F] }

  implicit def catsOptionTAsync[F[_]: Async]: Async[OptionT[F, ?]] =
    new OptionTAsync[F] { def F = Async[F] }

  implicit def catsStateTAsync[F[_]: Async, S]: Async[StateT[F, S, ?]] =
    new StateTAsync[F, S] { def F = Async[F] }

  implicit def catsWriterTAsync[F[_]: Async, L: Monoid]: Async[WriterT[F, L, ?]] =
    new WriterTAsync[F, L] { def F = Async[F]; def L = Monoid[L] }

  private[effect] trait EitherTAsync[F[_], L]
      extends Async[EitherT[F, L, ?]]
      with Sync.EitherTSync[F, L]
      with LiftIO.EitherTLiftIO[F, L] {

    override protected def F: Async[F]
    private implicit def _F = F
    protected def FF = F

    def async[A](k: (Either[Throwable, A] => Unit) => Unit): EitherT[F, L, A] =
      EitherT.liftF(F.async(k))
  }

  private[effect] trait OptionTAsync[F[_]]
      extends Async[OptionT[F, ?]]
      with Sync.OptionTSync[F]
      with LiftIO.OptionTLiftIO[F] {

    override protected def F: Async[F]
    private implicit def _F = F

    protected def FF = F

    def async[A](k: (Either[Throwable, A] => Unit) => Unit): OptionT[F, A] =
      OptionT.liftF(F.async(k))
  }

  private[effect] trait StateTAsync[F[_], S]
      extends Async[StateT[F, S, ?]]
      with Sync.StateTSync[F, S]
      with LiftIO.StateTLiftIO[F, S] {

    override protected def F: Async[F]
    private implicit def _F = F

    protected def FA = F

    def async[A](k: (Either[Throwable, A] => Unit) => Unit): StateT[F, S, A] =
      StateT.liftF(F.async(k))
  }

  private[effect] trait WriterTAsync[F[_], L]
      extends Async[WriterT[F, L, ?]]
      with Sync.WriterTSync[F, L]
      with LiftIO.WriterTLiftIO[F, L] {

    override protected def F: Async[F]
    private implicit def _F = F

    protected def FA = F

    private implicit def _L = L

    def async[A](k: (Either[Throwable, A] => Unit) => Unit): WriterT[F, L, A] =
      WriterT.liftF(F.async(k))
  }
}

object Async extends AsyncInstances
