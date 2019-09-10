/*
 * Copyright 2019 Daniel Spiewak
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

import cats.{Applicative, Eq, Eval, MonadError, Show, Traverse}
import cats.syntax.eq._
import cats.syntax.show._

sealed trait ExitCase[+E, +A] extends Product with Serializable {
  def fold[B](canceled: => B, errored: E => B, completed: A => B): B
}

object ExitCase {

  def fromEither[E, A](either: Either[E, A]): ExitCase[E, A] =
    either.fold(Errored(_), Completed(_))

  implicit def eq[E: Eq, A: Eq]: Eq[ExitCase[E, A]] = Eq instance {
    case (Canceled, Canceled) => true
    case (Errored(left), Errored(right)) => left === right
    case (Completed(left), Completed(right)) => left === right
    case _ => false
  }

  implicit def show[E: Show, A: Show]: Show[ExitCase[E, A]] = Show show {
    case Canceled => "Canceled"
    case Errored(left) => s"Errored(${left.show})"
    case Completed(right) => s"Completed(${right.show})"
  }

  implicit def instance[E]: MonadError[ExitCase[E, ?], E] with Traverse[ExitCase[E, ?]] =
    new MonadError[ExitCase[E, ?], E] with Traverse[ExitCase[E, ?]] {

      def pure[A](x: A): ExitCase[E, A] = Completed(x)

      def handleErrorWith[A](fa: ExitCase[E, A])(f: E => ExitCase[E, A]): ExitCase[E, A] =
        fa.fold(Canceled, f, Completed(_))

      def raiseError[A](e: E): ExitCase[E, A] = Errored(e)

      def flatMap[A, B](fa: ExitCase[E, A])(f: A => ExitCase[E, B]): ExitCase[E, B] =
        fa.fold(Canceled, Errored(_), f)

      def tailRecM[A, B](a: A)(f: A => ExitCase[E, Either[A, B]]): ExitCase[E, B] =
        f(a) match {
          case Completed(Left(a)) => tailRecM(a)(f)
          case Completed(Right(b)) => Completed(b)
          case Canceled => Canceled
          case Errored(e) => Errored(e)
        }

      def foldLeft[A, B](fa: ExitCase[E, A], b: B)(f: (B, A) => B): B =
        fa.fold(b, _ => b, a => f(b, a))

      def foldRight[A, B](fa: ExitCase[E, A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
        fa.fold(lb, _ => lb, a => f(a, lb))

      def traverse[G[_]: Applicative, A, B](fa: ExitCase[E, A])(f: A => G[B]): G[ExitCase[E, B]] = fa match {
        case Canceled => Applicative[G].pure(Canceled)
        case Errored(e) => Applicative[G].pure(Errored(e))
        case Completed(a) => Applicative[G].map(f(a))(Completed(_))
      }
    }

  final case class Completed[A](a: A) extends ExitCase[Nothing, A] {
    def fold[B](canceled: => B, errored: Nothing => B, completed: A => B): B =
      completed(a)
  }

  final case class Errored[E](e: E) extends ExitCase[E, Nothing] {
    def fold[B](canceled: => B, errored: E => B, completed: Nothing => B): B =
      errored(e)
  }

  case object Canceled extends ExitCase[Nothing, Nothing] {
    def fold[B](canceled: => B, errored: Nothing => B, completed: Nothing => B): B =
      canceled
  }
}
