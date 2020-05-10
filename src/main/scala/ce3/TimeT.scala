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

import cats.{~>, Functor, Group, Monad, Monoid}
import cats.data.Kleisli
import cats.implicits._

import scala.concurrent.duration._

/*
 * NB: Even though we expect this to be usable on implementations which
 * interpret to multiple underlying threads, we never have to worry about
 * race conditions on `now` since we ensure that `Time` instances are
 * unique per-fiber. Thus, a volatile var is sufficient.
 */
final class Time private[ce3] (@volatile private[ce3] var now: FiniteDuration) {
  private[ce3] def fork(): Time =
    new Time(now)
}

object TimeT {

  def liftF[F[_], A](fa: F[A]): TimeT[F, A] =
    Kleisli.liftF(fa)

  def run[F[_], A](tfa: TimeT[F, A]): F[A] =
    tfa.run(new Time(0.millis))

  implicit def groupTimeT[F[_]: Monad, A](implicit A: Group[A]): Group[TimeT[F, A]] =
    new Group[TimeT[F, A]] {

      def empty = Monoid[A].empty.pure[TimeT[F, ?]]

      def combine(left: TimeT[F, A], right: TimeT[F, A]) =
        (left, right).mapN(_ |+| _)

      def inverse(a: TimeT[F, A]) =
        a.map(_.inverse)
    }

  implicit def temporalB[F[_], E](implicit F: ConcurrentBracket[F, E]): TemporalBracket[TimeT[F, ?], E] =
    new Temporal[TimeT[F, ?], E] with Bracket[TimeT[F, ?], E] {

      def pure[A](x: A): TimeT[F, A] =
        Kleisli.pure(x)

      def handleErrorWith[A](fa: TimeT[F, A])(f: E => TimeT[F, A]): TimeT[F, A] =
        Kleisli { time =>
          F.handleErrorWith(fa.run(time))(f.andThen(_.run(time)))
        }

      def raiseError[A](e: E): TimeT[F, A] =
        TimeT.liftF(F.raiseError(e))

      def bracketCase[A, B](
          acquire: TimeT[F, A])(
          use: A => TimeT[F, B])(
          release: (A, Outcome[TimeT[F, ?], E, B]) => TimeT[F, Unit])
          : TimeT[F, B] =
        Kleisli { time =>
          F.bracketCase(acquire.run(time))(use.andThen(_.run(time))) { (a, oc) =>
            release(a, oc.mapK(Kleisli.liftK[F, Time])).run(time)
          }
        }

      val canceled: TimeT[F, Unit] =
        TimeT.liftF(F.canceled)

      val cede: TimeT[F, Unit] =
        TimeT.liftF(F.cede)

      def never[A]: TimeT[F, A] =
        TimeT.liftF(F.never[A])

      def racePair[A, B](
          fa: TimeT[F, A],
          fb: TimeT[F, B])
          : TimeT[F, Either[(A, Fiber[TimeT[F, ?], E, B]), (Fiber[TimeT[F, ?], E, A], B)]] =
        Kleisli { time =>
          val forkA = time.fork()
          val forkB = time.fork()

          F.racePair(fa.run(forkA), fb.run(forkB)) map {
            case Left((a, delegate)) =>
              time.now = forkA.now
              Left((a, fiberize(forkB, delegate)))

            case Right((delegate, b)) =>
              time.now = forkB.now
              Right((fiberize(forkA, delegate), b))
          }
        }

      def start[A](fa: TimeT[F, A]): TimeT[F, Fiber[TimeT[F, ?], E, A]] =
        for {
          time <- Kleisli.ask[F, Time]
          forked = time.fork()
          delegate <- Kleisli.liftF(F.start(fa.run(forked)))
        } yield fiberize(forked, delegate)

      def uncancelable[A](body: TimeT[F, ?] ~> TimeT[F, ?] => TimeT[F, A]): TimeT[F, A] =
        Kleisli { time =>
          F uncancelable { poll =>
            body(λ[TimeT[F, ?] ~> TimeT[F, ?]] { tfa =>
              Kleisli { time2 =>
                poll(tfa.run(time2))
              }
            }).run(time)
          }
        }

      def flatMap[A, B](fa: TimeT[F, A])(f: A => TimeT[F, B]): TimeT[F, B] =
        fa.flatMap(f)

      def tailRecM[A, B](a: A)(f: A => TimeT[F, Either[A, B]]): TimeT[F, B] =
        Kleisli { time =>
          F.tailRecM(a)(f.andThen(_.run(time)))
        }

      val now: TimeT[F, FiniteDuration] =
        Kleisli.ask[F, Time].map(_.now)

      def sleep(time: FiniteDuration): TimeT[F, Unit] =
        Kleisli.ask[F, Time].map(_.now += time)   // what could go wrong?

      private[this] def fiberize[A](forked: Time, delegate: Fiber[F, E, A]): Fiber[TimeT[F, ?], E, A] =
        new Fiber[TimeT[F, ?], E, A] {

          val cancel =
            Kleisli.liftF(delegate.cancel)

          val join =
            Kleisli { outerTime =>
              delegate.join map { back =>
                // if the forked time is less, then it means it completed first; if it's more, then we semantically block (by increasing "now")
                outerTime.now = outerTime.now.max(forked.now)
                back.mapK(Kleisli.liftK[F, Time])
              }
            }
        }
    }
}
