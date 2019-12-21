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

import cats.{~>, Eq, InjectK, MonadError, Monoid, Show, StackSafeMonad}
import cats.data.{EitherK, EitherT, StateT}
import cats.free.Free
import cats.syntax.apply._
import cats.syntax.applicative._
import cats.syntax.functor._

import scala.concurrent.duration.FiniteDuration

object playground {

  final case class Dispatch(ordinal: Int, canceled: Set[Int])

  object Dispatch {
    def empty: Dispatch = Dispatch(0, Set())

    implicit val monoid: Monoid[Dispatch] = new Monoid[Dispatch] {
      def empty = Dispatch.empty

      def combine(left: Dispatch, right: Dispatch): Dispatch =
        Dispatch(
          math.max(left.ordinal, right.ordinal),
          left.canceled ++ right.canceled)
    }

    implicit val eq: Eq[Dispatch] = Eq.fromUniversalEquals[Dispatch]

    implicit val show: Show[Dispatch] = Show.fromToString[Dispatch]
  }

  sealed trait StateF[S, A] extends Product with Serializable

  object StateF {
    final case class Get[S]() extends StateF[S, S]
    final case class Put[S](s: S) extends StateF[S, Unit]
  }

  object State {
    def get[F[_], S](implicit I: InjectK[StateF[S, ?], F]): Free[F, S] =
      Free.liftInject[F](StateF.Get[S](): StateF[S, S])

    def put[F[_], S](s: S)(implicit I: InjectK[StateF[S, ?], F]): Free[F, Unit] =
      Free.liftInject[F](StateF.Put(s): StateF[S, Unit])

    def modify[F[_], S](f: S => S)(implicit I: InjectK[StateF[S, ?], F]): Free[F, Unit] =
      get[F, S].flatMap(s => put[F, S](f(s)))

    def modifyF[F[_], S](f: S => Free[F, S])(implicit I: InjectK[StateF[S, ?], F]): Free[F, Unit] =
      get[F, S].flatMap(f(_).flatMap(put[F, S](_)))
  }

  type PureIOF[E, A] =
    EitherK[
      StateF[Dispatch, ?],    // cancelation and fiber ids
      EitherK[
        StateF[Boolean, ?],   // cancelability
        ExitCase[E, ?],
        ?],
      A]

  type PureIO[E, A] = Free[PureIOF[E, ?], A]

  object PureIO {

    def run[E, A](ioa: PureIO[E, A]): ExitCase[E, A] =
      eval(Dispatch.empty, ioa)._2

    private def eval[E, A](d0: Dispatch, ioa: PureIO[E, A]): (Dispatch, ExitCase[E, A]) = {
      // you know, if Free's internals were just public...
      var d: Dispatch = d0

      val ec = ioa foldMap λ[PureIOF[E, ?] ~> ExitCase[E, ?]] { ek =>
        ek.run match {
          // can't use StateF.Get() for reasons
          case Left(_: StateF.Get[Dispatch]) =>
            ExitCase.Completed(d)

          case Left(StateF.Put(d2)) =>
            d = d2
            ExitCase.Completed(())

          case Right(ec) =>
            ???
            // ec
        }
      }

      (d, ec)
    }

    implicit def concurrentB[E]: Concurrent[PureIO[E, ?], E] with Bracket[PureIO[E, ?], E] =
      new Concurrent[PureIO[E, ?], E] with Bracket[PureIO[E, ?], E] with StackSafeMonad[PureIO[E, ?]] {
        def pure[A](x: A): PureIO[E, A] = Free.pure(x)

        def handleErrorWith[A](fa: PureIO[E, A])(f: E => PureIO[E, A]): PureIO[E, A] =
          State.get[PureIOF[E, ?], Dispatch] flatMap { d =>
            val (d2, ec) = eval(d, fa)
            State.put[PureIOF[E, ?], Dispatch](d2) *> ec.fold(canceled[A](???), f, pure(_))
          }

        def raiseError[A](e: E): PureIO[E, A] =
          Free.liftInject[PureIOF[E, ?]](ExitCase.Errored(e): ExitCase[E, A])

        def bracketCase[A, B](
            acquire: PureIO[E, A])(
            use: A => PureIO[E, B])(
            release: (A, ExitCase[E, B]) => PureIO[E, Unit])
            : PureIO[E, B] = for {

          // most of this juggle is just to ensure that acquire is uncancelable
          // the second half is ensuring that anything completed within acquire is propagated
          d0 <- State.get[PureIOF[E, ?], Dispatch]
          sheltered = d0.copy(canceled = Set())
          _ <- State.put[PureIOF[E, ?], Dispatch](sheltered)
          ea <- attempt(acquire)
          d2 <- State.get[PureIOF[E, ?], Dispatch]
          d3 = d2.copy(canceled = d2.canceled ++ d0.canceled)
          _ <- State.put[PureIOF[E, ?], Dispatch](d3)

          a <- ea.fold(raiseError[A](_), pure(_))

          // we use eval here rather than attempt because it surfaces cancelation
          (d4, ecb) = eval(d3, use(a))
          _ <- State.put[PureIOF[E, ?], Dispatch](d4)

          _ <- release(a, ecb)
          b <- Free.liftInject[PureIOF[E, ?]](ecb)
        } yield b

        def uncancelable[A](
            body: (PureIO[E, ?] ~> PureIO[E, ?]) => PureIO[E, A])
            : PureIO[E, A]
            = ???

        def canceled[A](fallback: A): PureIO[E, A] =
          Free.liftInject[PureIOF[E, ?]](ExitCase.Canceled: ExitCase[E, A])

        def never[A]: PureIO[E, A] = ???

        def start[A](fa: PureIO[E, A]): PureIO[E, Fiber[PureIO[E, ?], E, A]] =
          State.get[PureIOF[E, ?], Dispatch] flatMap { d =>
            val eff = State.put[PureIOF[E, ?], Dispatch](d.copy(ordinal = d.ordinal + 1))
            eff.as(new OurFiber(d.ordinal, fa))
          }

        def racePair[A, B](
            fa: PureIO[E, A],
            fb: PureIO[E, B])
            : PureIO[E, Either[(A, Fiber[PureIO[E, ?], E, B]), (Fiber[PureIO[E, ?], E, A], B)]] = for {
          fiberA <- start(fa)
          fiberB <- start(fb)

          // ensure that we don't always pick one side or another
          d <- State.get[PureIOF[E, ?], Dispatch]
          biasLeft = d.ordinal % 2 == 0

          evaluated <- if (biasLeft)
            fiberA.join.map(Left(_))
          else
            fiberB.join.map(Right(_))

          // this implements the racePair yield laws
          normalized <- evaluated match {
            case Left(ExitCase.Canceled | ExitCase.Errored(_)) =>
              fiberB.join.map(Right(_))

            case Right(ExitCase.Canceled | ExitCase.Errored(_)) =>
              fiberA.join.map(Left(_))

            case e => pure(e)
          }

          back <- normalized match {
            case Left(ec) =>
              Free.liftInject[PureIOF[E, ?]](ec.map(a => Left((a, fiberB))))

            case Right(ec) =>
              Free.liftInject[PureIOF[E, ?]](ec.map(b => Right((fiberA, b))))
          }
        } yield back

        def cede: PureIO[E, Unit] = unit

        def flatMap[A, B](fa: PureIO[E, A])(f: A => PureIO[E, B]): PureIO[E, B] =
          fa.flatMap(f)

        private final class OurFiber[A](ordinal: Int, run: PureIO[E, A]) extends Fiber[PureIO[E, ?], E, A] {

          def cancel = State.modify[PureIOF[E, ?], Dispatch] { d =>
            d.copy(canceled = d.canceled + ordinal)
          }

          def join =
            State.get[PureIOF[E, ?], Dispatch] flatMap { d =>
              if (d.canceled(ordinal)) {
                pure(ExitCase.Canceled)
              } else {
                val (d2, ec) = eval(d, run)
                State.put[PureIOF[E, ?], Dispatch](d2).as(ec)
              }
            }
        }
      }
  }
}
