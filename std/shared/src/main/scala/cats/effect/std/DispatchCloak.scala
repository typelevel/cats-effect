/*
 * Copyright 2020-2021 Typelevel
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

package cats.effect.std

import cats.effect.kernel.Outcome
import cats.effect.kernel.Spawn
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

import cats.data.OptionT

import scala.util.control.NoStackTrace

/**
 * Construct that "clocks" non-successful outcomes as Throwables
 * to traverse dispatcher layers.
 */
abstract class DispatchCloak[F[_]](implicit F: Spawn[F]) {

  def cloak[A](fa: F[A]): F[Either[Throwable, A]]

  def uncloak[A](t: Throwable): Option[F[A]]

  def guaranteeCloak[A](fa: F[A]): F[Either[Throwable, A]] = {
    fa.start.flatMap(_.join).flatMap {
      case Outcome.Succeeded(fa) => cloak(fa)
      case Outcome.Errored(e) => F.pure(Left(e))
      case Outcome.Canceled() => F.pure(Left(DispatchCloak.CancelCloak))
    }
  }

  def recoverCloaked[A](fa: F[A]) = {
    fa.handleErrorWith {
      case DispatchCloak.CancelCloak => F.canceled.asInstanceOf[F[A]]
      case other => uncloak[A](other).getOrElse(F.raiseError(other))
    }
  }

}

object DispatchCloak extends LowPriorityDispatchCloakImplicits {

  def apply[F[_]](implicit instance: DispatchCloak[F]): DispatchCloak[F] = instance

  case object CancelCloak extends Throwable with NoStackTrace

  implicit def cloackForOptionT[F[_]: Spawn]: DispatchCloak[OptionT[F, *]] =
    new DispatchCloak[OptionT[F, *]] {
      // Path dependant class to ensure this instance doesn't intercept
      // instances belonging to other transformer layers.
      private case object NoneCloak extends Throwable with NoStackTrace

      def cloak[A](fa: OptionT[F, A]): OptionT[F, Either[Throwable, A]] =
        fa.map(_.asRight[Throwable]).orElse(OptionT.pure(NoneCloak.asLeft[A]))

      def uncloak[A](t: Throwable): Option[OptionT[F, A]] =
        if (t == NoneCloak) Some(OptionT.none[F, A]) else None
    }

}

private[std] trait LowPriorityDispatchCloakImplicits {

  implicit def cloakForSpawn[F[_]: Spawn]: DispatchCloak[F] = new DispatchCloak[F] {
    def cloak[A](fa: F[A]): F[Either[Throwable, A]] =
      fa.map(a => Right(a))

    def uncloak[A](t: Throwable): Option[F[A]] = None
  }

}
