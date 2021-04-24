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
 * Construct that "cloaks" non-successful outcomes as path-dependant Throwables,
 * allowing to traverse dispatcher layers and be recovered in effect-land on
 * the other side of the unsafe region.
 *
 * In particular, side error channels that the kernel typeclasses do not know about,
 * such as the ones of OptionT/EitherT/IorT, can be accounted for using this construct,
 * making it possible to interoperate with impure semantics, in polymorphic ways.
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

  // A marker exception to communicate cancellation through dispatch runtimes.
  case object CancelCloak extends Throwable with NoStackTrace

  implicit def cloakForOptionT[F[_]: Spawn: DispatchCloak]: DispatchCloak[OptionT[F, *]] =
    new DispatchCloak[OptionT[F, *]] {
      // Path dependant object to ensure this doesn't intercept instances
      // cloaked Nones belonging to other OptionT layers.
      private case object NoneCloak extends Throwable with NoStackTrace

      def cloak[A](fa: OptionT[F, A]): OptionT[F, Either[Throwable, A]] = {
        OptionT.liftF(
          DispatchCloak[F].cloak(fa.value).map(_.sequence.getOrElse(Left(this.NoneCloak))))
      }

      def uncloak[A](t: Throwable): Option[OptionT[F, A]] = t match {
        case this.NoneCloak => Some(OptionT.none[F, A])
        case other => DispatchCloak[F].uncloak(other).map(OptionT.liftF[F, A])
      }
    }

}

private[std] trait LowPriorityDispatchCloakImplicits {

  /**
   * This should be the default instance for anything that does not have a side
   * error channel that might prevent calls such `Dispatcher#unsafeRunSync` from
   * terminating due to the kernel typeclasses not having knowledge of.
   */
  implicit def defaultCloak[F[_]: Spawn]: DispatchCloak[F] = new DispatchCloak[F] {
    def cloak[A](fa: F[A]): F[Either[Throwable, A]] =
      fa.map(a => Right(a))

    def uncloak[A](t: Throwable): Option[F[A]] = None
  }

}
