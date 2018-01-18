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

import cats.data.{EitherT, Kleisli, OptionT, StateT, WriterT}

import scala.annotation.implicitNotFound

@typeclass
@implicitNotFound("""Cannot find implicit value for LiftIO[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Strategy or some equivalent type.""")
trait LiftIO[F[_]] {
  def liftIO[A](ioa: IO[A]): F[A]
}

private[effect] abstract class LiftIOInstances {

  implicit def catsEitherTLiftIO[F[_]: LiftIO: Functor, L]: LiftIO[EitherT[F, L, ?]] =
    new EitherTLiftIO[F, L] { def F = LiftIO[F]; def FF = Functor[F] }

  implicit def catsKleisliLiftIO[F[_]: LiftIO, R]: LiftIO[Kleisli[F, R, ?]] =
    new KleisliLiftIO[F, R] { def F = LiftIO[F] }

  implicit def catsOptionTLiftIO[F[_]: LiftIO: Functor]: LiftIO[OptionT[F, ?]] =
    new OptionTLiftIO[F] { def F = LiftIO[F]; def FF = Functor[F] }

  implicit def catsStateTLiftIO[F[_]: LiftIO: Applicative, S]: LiftIO[StateT[F, S, ?]] =
    new StateTLiftIO[F, S] { def F = LiftIO[F]; def FA = Applicative[F] }

  implicit def catsWriterTLiftIO[F[_]: LiftIO: Applicative, L: Monoid]: LiftIO[WriterT[F, L, ?]] =
    new WriterTLiftIO[F, L] { def F = LiftIO[F]; def FA = Applicative[F]; def L = Monoid[L] }

  private[effect] trait EitherTLiftIO[F[_], L] extends LiftIO[EitherT[F, L, ?]] {
    protected def F: LiftIO[F]
    protected def FF: Functor[F]
    private implicit def _FF = FF

    override def liftIO[A](ioa: IO[A]): EitherT[F, L, A] =
      EitherT.liftF(F.liftIO(ioa))
  }

  private[effect] trait KleisliLiftIO[F[_], R] extends LiftIO[Kleisli[F, R, ?]] {
    protected def F: LiftIO[F]

    override def liftIO[A](ioa: IO[A]): Kleisli[F, R, A] =
      Kleisli.liftF(F.liftIO(ioa))
  }

  private[effect] trait OptionTLiftIO[F[_]] extends LiftIO[OptionT[F, ?]] {
    protected def F: LiftIO[F]

    protected def FF: Functor[F]
    private implicit def _FF = FF

    override def liftIO[A](ioa: IO[A]): OptionT[F, A] =
      OptionT.liftF(F.liftIO(ioa))
  }

  private[effect] trait StateTLiftIO[F[_], S] extends LiftIO[StateT[F, S, ?]] {
    protected def F: LiftIO[F]
    protected def FA: Applicative[F]
    private implicit def _FA = FA

    override def liftIO[A](ioa: IO[A]): StateT[F, S, A] = StateT.liftF(F.liftIO(ioa))
  }

  private[effect] trait WriterTLiftIO[F[_], L] extends LiftIO[WriterT[F, L, ?]] {
    protected def F: LiftIO[F]

    protected def FA: Applicative[F]
    private implicit def _FA = FA

    protected def L: Monoid[L]
    private implicit def _L = L

    override def liftIO[A](ioa: IO[A]): WriterT[F, L, A] =
      WriterT.liftF(F.liftIO(ioa))
  }
}

object LiftIO extends LiftIOInstances
