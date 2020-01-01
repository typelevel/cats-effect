/*
 * Copyright 2020 Daniel Spiewak
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

package ce3

import cats.{~>, Applicative, ApplicativeError, Eq, Eval, Monad, MonadError, Show, Traverse}
import cats.implicits._

import scala.annotation.tailrec
import scala.util.{Either, Left, Right}

sealed trait ExitCase[+F[_], +E, +A] extends Product with Serializable

private[ce3] trait LowPriorityImplicits {
  import ExitCase.{Canceled, Completed, Errored}

  // variant for when F[A] doesn't have a Show (which is, like, most of the time)
  implicit def showUnknown[F[_], E: Show, A]: Show[ExitCase[F, E, A]] = Show show {
    case Canceled => "Canceled"
    case Errored(left) => s"Errored(${left.show})"
    case Completed(right) => s"Completed(<unknown>)"
  }

  implicit def applicativeError[F[_]: Applicative, E]: ApplicativeError[ExitCase[F, E, ?], E] =
    new ExitCaseApplicativeError[F, E]

  protected class ExitCaseApplicativeError[F[_]: Applicative, E] extends ApplicativeError[ExitCase[F, E, ?], E] {

    def pure[A](x: A): ExitCase[F, E, A] = Completed(x.pure[F])

    def handleErrorWith[A](fa: ExitCase[F, E, A])(f: E => ExitCase[F, E, A]): ExitCase[F, E, A] =
      fa.fold(Canceled, f, Completed(_: F[A]))

    def raiseError[A](e: E): ExitCase[F, E, A] = Errored(e)

    def ap[A, B](ff: ExitCase[F, E, A => B])(fa: ExitCase[F, E, A]): ExitCase[F, E, B] =
      (ff, fa) match {
        case (c: Completed[F, A => B], Completed(fa)) =>
          Completed(c.fa.ap(fa))

        case (Errored(e), _) =>
          Errored(e)

        case (Canceled, _) =>
          Canceled

        case (_, Errored(e)) =>
          Errored(e)

        case (_, Canceled) =>
          Canceled
      }
  }
}

object ExitCase extends LowPriorityImplicits {

  def fromEither[F[_]: Applicative, E, A](either: Either[E, A]): ExitCase[F, E, A] =
    either.fold(Errored(_), a => Completed(a.pure[F]))

  implicit class Syntax[F[_], E, A](val self: ExitCase[F, E, A]) extends AnyVal {

    def fold[B](
        canceled: => B,
        errored: E => B,
        completed: F[A] => B)
        : B = self match {
      case Canceled => canceled
      case Errored(e) => errored(e)
      case Completed(fa) => completed(fa)
    }

    def mapK[G[_]](f: F ~> G): ExitCase[G, E, A] = self match {
      case ExitCase.Canceled => ExitCase.Canceled
      case ExitCase.Errored(e) => ExitCase.Errored(e)
      case ExitCase.Completed(fa) => ExitCase.Completed(f(fa))
    }
  }

  implicit def eq[F[_], E: Eq, A](implicit FA: Eq[F[A]]): Eq[ExitCase[F, E, A]] = Eq instance {
    case (Canceled, Canceled) => true
    case (Errored(left), Errored(right)) => left === right
    case (Completed(left), Completed(right)) => left === right
    case _ => false
  }

  implicit def show[F[_], E: Show, A](implicit FA: Show[F[A]]): Show[ExitCase[F, E, A]] = Show show {
    case Canceled => "Canceled"
    case Errored(left) => s"Errored(${left.show})"
    case Completed(right) => s"Completed(${right.show})"
  }

  implicit def monadError[F[_]: Traverse, E](implicit F: Monad[F]): MonadError[ExitCase[F, E, ?], E] =
    new ExitCaseApplicativeError[F, E]()(F) with MonadError[ExitCase[F, E, ?], E] {

      def flatMap[A, B](fa: ExitCase[F, E, A])(f: A => ExitCase[F, E, B]): ExitCase[F, E, B] = fa match {
        case Completed(ifa) =>
          Traverse[F].traverse(ifa)(f) match {
            case Completed(iffa) => Completed(Monad[F].flatten(iffa))
            case Errored(e) => Errored(e)
            case Canceled => Canceled
          }

        case Errored(e) => Errored(e)
        case Canceled => Canceled
      }

      @tailrec
      def tailRecM[A, B](a: A)(f: A => ExitCase[F, E, Either[A, B]]): ExitCase[F, E, B] =
        f(a) match {
          case c: Completed[F, Either[A, B]] =>
            Traverse[F].sequence(c.fa) match {
              case Left(a) => tailRecM(a)(f)
              case Right(fb) => Completed(fb)
            }

          case Errored(e) => Errored(e)
          case Canceled => Canceled
        }
    }

  final case class Completed[F[_], A](fa: F[A]) extends ExitCase[F, Nothing, A]
  final case class Errored[E](e: E) extends ExitCase[Nothing, E, Nothing]
  case object Canceled extends ExitCase[Nothing, Nothing, Nothing]
}
