/*
 * Copyright 2020 Typelevel
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

package cats.effect.kernel

import cats.{Applicative, ApplicativeError, Bifunctor, Eq}
import cats.{~>, Monad, MonadError, Order, Show, Traverse}
import cats.syntax.all._

import scala.annotation.tailrec
import scala.util.{Either, Left, Right}

sealed trait Outcome[F[_], E, A] extends Product with Serializable {
  import Outcome._

  def embed(onCancel: F[A])(implicit F: MonadCancel[F, E]): F[A] =
    fold(onCancel, F.raiseError, identity)

  def embedNever(implicit F: GenSpawn[F, E]): F[A] =
    embed(F.never)

  def fold[B](canceled: => B, errored: E => B, completed: F[A] => B): B =
    this match {
      case Canceled() => canceled
      case Errored(e) => errored(e)
      case Completed(fa) => completed(fa)
    }

  def mapK[G[_]](f: F ~> G): Outcome[G, E, A] =
    this match {
      case Canceled() => Canceled()
      case Errored(e) => Errored(e)
      case Completed(fa) => Completed(f(fa))
    }
}

private[kernel] trait LowPriorityImplicits {
  import Outcome.{Canceled, Completed, Errored}

  // variant for when F[A] doesn't have a Show (which is, like, most of the time)
  implicit def showUnknown[F[_], E, A](implicit E: Show[E]): Show[Outcome[F, E, A]] =
    Show show {
      case Canceled() => "Canceled"
      case Errored(left) => s"Errored(${left.show})"
      case Completed(_) => s"Completed(<unknown>)"
    }

  implicit def eq[F[_], E: Eq, A](implicit FA: Eq[F[A]]): Eq[Outcome[F, E, A]] =
    Eq instance {
      case (Canceled(), Canceled()) => true
      case (Errored(left), Errored(right)) => left === right
      case (Completed(left), Completed(right)) => left === right
      case _ => false
    }

  implicit def applicativeError[F[_], E](
      implicit F: Applicative[F]): ApplicativeError[Outcome[F, E, *], E] =
    new OutcomeApplicativeError[F, E]

  protected class OutcomeApplicativeError[F[_]: Applicative, E]
      extends ApplicativeError[Outcome[F, E, *], E]
      with Bifunctor[Outcome[F, *, *]] {

    def pure[A](x: A): Outcome[F, E, A] = Completed(x.pure[F])

    def handleErrorWith[A](fa: Outcome[F, E, A])(f: E => Outcome[F, E, A]): Outcome[F, E, A] =
      fa.fold(Canceled(), f, Completed(_: F[A]))

    def raiseError[A](e: E): Outcome[F, E, A] = Errored(e)

    def ap[A, B](ff: Outcome[F, E, A => B])(fa: Outcome[F, E, A]): Outcome[F, E, B] =
      (ff, fa) match {
        case (Completed(cfa), Completed(fa)) =>
          Completed(cfa.ap(fa))

        case (Errored(e), _) =>
          Errored(e)

        case (Canceled(), _) =>
          Canceled()

        case (_, Errored(e)) =>
          Errored(e)

        case (_, Canceled()) =>
          Canceled()
      }

    def bimap[A, B, C, D](fab: Outcome[F, A, B])(f: A => C, g: B => D): Outcome[F, C, D] =
      fab match {
        case Completed(fa) => Completed(fa.map(g))
        case Errored(e) => Errored(f(e))
        case Canceled() => Canceled()
      }
  }
}

object Outcome extends LowPriorityImplicits {

  def completed[F[_], E, A](fa: F[A]): Outcome[F, E, A] =
    Completed(fa)

  def errored[F[_], E, A](e: E): Outcome[F, E, A] =
    Errored(e)

  def canceled[F[_], E, A]: Outcome[F, E, A] =
    Canceled()

  def fromEither[F[_]: Applicative, E, A](either: Either[E, A]): Outcome[F, E, A] =
    either.fold(Errored(_), a => Completed(a.pure[F]))

  implicit def order[F[_], E: Order, A](implicit FA: Order[F[A]]): Order[Outcome[F, E, A]] =
    Order.from {
      case (Canceled(), Canceled()) => 0
      case (Errored(left), Errored(right)) => left.compare(right)
      case (Completed(lfa), Completed(rfa)) => lfa.compare(rfa)

      case (Canceled(), _) => -1
      case (_, Canceled()) => 1
      case (Errored(_), Completed(_)) => -1
      case (Completed(_), Errored(_)) => 1
    }

  implicit def show[F[_], E, A](implicit FA: Show[F[A]], E: Show[E]): Show[Outcome[F, E, A]] =
    Show show {
      case Canceled() => "Canceled"
      case Errored(left) => s"Errored(${left.show})"
      case Completed(right) => s"Completed(${right.show})"
    }

  implicit def monadError[F[_], E](
      implicit F: Monad[F],
      FT: Traverse[F]): MonadError[Outcome[F, E, *], E] =
    new OutcomeApplicativeError[F, E]()(F) with MonadError[Outcome[F, E, *], E] {

      override def map[A, B](fa: Outcome[F, E, A])(f: A => B): Outcome[F, E, B] =
        bimap(fa)(identity, f)

      def flatMap[A, B](fa: Outcome[F, E, A])(f: A => Outcome[F, E, B]): Outcome[F, E, B] =
        fa match {
          case Completed(ifa) =>
            Traverse[F].traverse(ifa)(f) match {
              case Completed(ifaa) => Completed(Monad[F].flatten(ifaa))
              case Errored(e) => Errored(e)
              case Canceled() => Canceled()
            }

          case Errored(e) => Errored(e)
          case Canceled() => Canceled()
        }

      @tailrec
      def tailRecM[A, B](a: A)(f: A => Outcome[F, E, Either[A, B]]): Outcome[F, E, B] =
        f(a) match {
          case Completed(fa) =>
            Traverse[F].sequence[Either[A, *], B](fa) match { // Dotty can't infer this
              case Left(a) => tailRecM(a)(f)
              case Right(fb) => Completed(fb)
            }

          case Errored(e) => Errored(e)
          case Canceled() => Canceled()
        }
    }

  final case class Completed[F[_], E, A](fa: F[A]) extends Outcome[F, E, A]
  final case class Errored[F[_], E, A](e: E) extends Outcome[F, E, A]
  final case class Canceled[F[_], E, A]() extends Outcome[F, E, A]
}
