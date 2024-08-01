/*
 * Copyright 2020-2024 Typelevel
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

import cats.{
  ~>,
  Applicative,
  ApplicativeError,
  Bifunctor,
  Eq,
  Monad,
  MonadError,
  Order,
  Show,
  Traverse
}
import cats.syntax.all._

import scala.annotation.tailrec
import scala.util.{Either, Left, Right}

/**
 * Represents the result of the execution of a fiber. It may terminate in one of 3 states:
 *   1. Succeeded(fa) The fiber completed with a value.
 *
 * A commonly asked question is why this wraps a value of type `F[A]` rather than one of type
 * `A`. This is to support monad transformers. Consider
 *
 * {{{
 * val oc: OptionT[IO, Outcome[OptionT[IO, *], Throwable, Int]] =
 *   for {
 *     fiber <- Spawn[OptionT[IO, *]].start(OptionT.none[IO, Int])
 *     oc <- fiber.join
 *   } yield oc
 * }}}
 *
 * If the fiber succeeds then there is no value of type `Int` to be wrapped in `Succeeded`,
 * hence `Succeeded` contains a value of type `OptionT[IO, Int]` instead:
 *
 * {{{
 * def run: IO[Unit] =
 *   for {
 *     res <- oc.flatMap(_.embedNever).value // `res` is `Option[Int]` here
 *     _ <- Console[IO].println(res) // prints "None"
 *   } yield ()
 * }}}
 *
 * In general you can assume that binding on the value of type `F[A]` contained in `Succeeded`
 * does not perform further effects. In the case of `IO` that means that the outcome has been
 * constructed as `Outcome.Succeeded(IO.pure(result))`.
 *
 * 2. Errored(e) The fiber exited with an error.
 *
 * 3. Canceled() The fiber was canceled, either externally or self-canceled via
 * `MonadCancel[F]#canceled`.
 */
sealed trait Outcome[F[_], E, A] extends Product with Serializable {
  import Outcome._

  def embed(onCancel: F[A])(implicit F: MonadCancel[F, E]): F[A] =
    fold(onCancel, F.raiseError, identity)

  def embedNever(implicit F: GenSpawn[F, E]): F[A] =
    embed(F.never)

  /**
   * Allows the restoration to a normal development flow from an Outcome.
   *
   * This can be useful for storing the state of a running computation and then waiters for that
   * data can act and continue forward on that shared outcome. Cancelation is encoded as a
   * `CancellationException`.
   */
  def embedError(implicit F: MonadCancel[F, E], ev: Throwable <:< E): F[A] =
    embed(
      F.raiseError(ev(new java.util.concurrent.CancellationException("Outcome was Canceled"))))

  def fold[B](canceled: => B, errored: E => B, completed: F[A] => B): B =
    this match {
      case Canceled() => canceled
      case Errored(e) => errored(e)
      case Succeeded(fa) => completed(fa)
    }

  def mapK[G[_]](f: F ~> G): Outcome[G, E, A] =
    this match {
      case Canceled() => Canceled()
      case Errored(e) => Errored(e)
      case Succeeded(fa) => Succeeded(f(fa))
    }

  def isSuccess: Boolean = this match {
    case Canceled() => false
    case Errored(_) => false
    case Succeeded(_) => true
  }

  def isError: Boolean = this match {
    case Canceled() => false
    case Errored(_) => true
    case Succeeded(_) => false
  }

  def isCanceled: Boolean = this match {
    case Canceled() => true
    case Errored(_) => false
    case Succeeded(_) => false
  }
}

private[kernel] trait LowPriorityImplicits {
  import Outcome.{Canceled, Errored, Succeeded}

  // variant for when F[A] doesn't have a Show (which is, like, most of the time)
  implicit def showUnknown[F[_], E, A](implicit E: Show[E]): Show[Outcome[F, E, A]] =
    Show show {
      case Canceled() => "Canceled"
      case Errored(left) => s"Errored(${left.show})"
      case Succeeded(_) => s"Succeeded(...)"
    }

  implicit def eq[F[_], E: Eq, A](implicit FA: Eq[F[A]]): Eq[Outcome[F, E, A]] =
    Eq instance {
      case (Canceled(), Canceled()) => true
      case (Errored(left), Errored(right)) => left === right
      case (Succeeded(left), Succeeded(right)) => left === right
      case _ => false
    }

  implicit def applicativeError[F[_], E](
      implicit F: Applicative[F]): ApplicativeError[Outcome[F, E, *], E] =
    new OutcomeApplicativeError[F, E]

  protected class OutcomeApplicativeError[F[_]: Applicative, E]
      extends ApplicativeError[Outcome[F, E, *], E]
      with Bifunctor[Outcome[F, *, *]] {

    def pure[A](x: A): Outcome[F, E, A] = Succeeded(x.pure[F])

    def handleErrorWith[A](fa: Outcome[F, E, A])(f: E => Outcome[F, E, A]): Outcome[F, E, A] =
      fa.fold(Canceled(), f, Succeeded(_: F[A]))

    def raiseError[A](e: E): Outcome[F, E, A] = Errored(e)

    def ap[A, B](ff: Outcome[F, E, A => B])(fa: Outcome[F, E, A]): Outcome[F, E, B] =
      (ff, fa) match {
        case (Succeeded(cfa), Succeeded(fa)) =>
          Succeeded(cfa.ap(fa))

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
        case Succeeded(fa) => Succeeded(fa.map(g))
        case Errored(e) => Errored(f(e))
        case Canceled() => Canceled()
      }
  }
}

object Outcome extends LowPriorityImplicits {

  def succeeded[F[_], E, A](fa: F[A]): Outcome[F, E, A] =
    Succeeded(fa)

  def errored[F[_], E, A](e: E): Outcome[F, E, A] =
    Errored(e)

  def canceled[F[_], E, A]: Outcome[F, E, A] =
    Canceled()

  def fromEither[F[_]: Applicative, E, A](either: Either[E, A]): Outcome[F, E, A] =
    either.fold(Errored(_), a => Succeeded(a.pure[F]))

  implicit def order[F[_], E: Order, A](implicit FA: Order[F[A]]): Order[Outcome[F, E, A]] =
    Order.from {
      case (Canceled(), Canceled()) => 0
      case (Errored(left), Errored(right)) => left.compare(right)
      case (Succeeded(lfa), Succeeded(rfa)) => lfa.compare(rfa)

      case (Canceled(), _) => -1
      case (_, Canceled()) => 1
      case (Errored(_), Succeeded(_)) => -1
      case (Succeeded(_), Errored(_)) => 1
    }

  implicit def show[F[_], E, A](implicit FA: Show[F[A]], E: Show[E]): Show[Outcome[F, E, A]] =
    Show show {
      case Canceled() => "Canceled"
      case Errored(left) => s"Errored(${left.show})"
      case Succeeded(right) => s"Succeeded(${right.show})"
    }

  implicit def monadError[F[_], E](
      implicit F: Monad[F],
      FT: Traverse[F]): MonadError[Outcome[F, E, *], E] =
    new OutcomeApplicativeError[F, E]()(F) with MonadError[Outcome[F, E, *], E] {

      override def map[A, B](fa: Outcome[F, E, A])(f: A => B): Outcome[F, E, B] =
        bimap(fa)(identity, f)

      def flatMap[A, B](fa: Outcome[F, E, A])(f: A => Outcome[F, E, B]): Outcome[F, E, B] =
        fa match {
          case Succeeded(ifa) =>
            Traverse[F].traverse(ifa)(f) match {
              case Succeeded(ifaa) => Succeeded(Monad[F].flatten(ifaa))
              case Errored(e) => Errored(e)
              case Canceled() => Canceled()
            }

          case Errored(e) => Errored(e)
          case Canceled() => Canceled()
        }

      @tailrec
      def tailRecM[A, B](a0: A)(f: A => Outcome[F, E, Either[A, B]]): Outcome[F, E, B] =
        f(a0) match {
          case Succeeded(fa) =>
            Traverse[F].sequence[Either[A, *], B](fa) match { // Dotty can't infer this
              case Left(a) => tailRecM(a)(f)
              case Right(fb) => Succeeded(fb)
            }

          case Errored(e) => Errored(e)
          case Canceled() => Canceled()
        }
    }

  final case class Succeeded[F[_], E, A](fa: F[A]) extends Outcome[F, E, A]
  final case class Errored[F[_], E, A](e: E) extends Outcome[F, E, A]
  final case class Canceled[F[_], E, A]() extends Outcome[F, E, A]
}
