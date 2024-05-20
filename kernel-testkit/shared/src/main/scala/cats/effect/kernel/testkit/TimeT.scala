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

package cats.effect
package kernel
package testkit

import cats.{~>, Group, Monad, Monoid, Order}
import cats.data.Kleisli
import cats.syntax.all._

import org.scalacheck.{Arbitrary, Cogen, Gen}

import scala.concurrent.duration._

import java.util.concurrent.TimeUnit

/*
 * NB: Even though we expect this to be usable on implementations which
 * interpret to multiple underlying threads, we never have to worry about
 * race conditions on `now` since we ensure that `Time` instances are
 * unique per-fiber. Thus, a volatile var is sufficient.
 */
private[effect] final class Time private[effect] (
    @volatile private[effect] var now: FiniteDuration) {
  private[effect] def fork(): Time =
    new Time(now)
}

private[effect] object Time {

  implicit def cogenTime: Cogen[Time] =
    Cogen[FiniteDuration].contramap(_.now)

  implicit def arbTime: Arbitrary[Time] =
    Arbitrary(Arbitrary.arbitrary[FiniteDuration].map(new Time(_)))

}

private[effect] object TimeT {

  def liftF[F[_], A](fa: F[A]): TimeT[F, A] =
    Kleisli.liftF(fa)

  def liftK[F[_]]: F ~> TimeT[F, *] =
    new (F ~> TimeT[F, *]) {
      override def apply[A](fa: F[A]): TimeT[F, A] = liftF(fa)
    }

  def run[F[_], A](tfa: TimeT[F, A]): F[A] =
    tfa.run(new Time(0.millis))

  // This possibly shouldn't be here but all the tests using TimeT import TimeT._ anyway
  implicit def arbPositiveFiniteDuration: Arbitrary[FiniteDuration] = {
    import TimeUnit._

    val genTU =
      Gen.oneOf(NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS)

    Arbitrary {
      genTU flatMap { u => Gen.posNum[Long].map(FiniteDuration(_, u)) }
    }
  }

  implicit def cogenForTimeT[F[_], A](implicit F: Cogen[Time => F[A]]): Cogen[TimeT[F, A]] =
    F.contramap(_.run)

  implicit def groupTimeT[F[_]: Monad, A](implicit A: Group[A]): Group[TimeT[F, A]] =
    new Group[TimeT[F, A]] {

      val empty = Monoid[A].empty.pure[TimeT[F, *]]

      def combine(left: TimeT[F, A], right: TimeT[F, A]) =
        (left, right).mapN(_ |+| _)

      def inverse(a: TimeT[F, A]) =
        a.map(_.inverse())
    }

  implicit def orderTimeT[F[_], A](implicit FA: Order[F[A]]): Order[TimeT[F, A]] =
    Order.by(TimeT.run)

  implicit def genTemporalForTimeT[F[_], E](
      implicit F: GenConcurrent[F, E]): GenTemporal[TimeT[F, *], E] =
    new TimeTGenTemporal[F, E]

  private[this] class TimeTGenTemporal[F[_], E](implicit FF: GenConcurrent[F, E])
      extends GenTemporal[TimeT[F, *], E]
      with GenConcurrent.KleisliGenConcurrent[F, Time, E] {
    protected def F: GenConcurrent[F, E] = FF

    override def racePair[A, B](fa: TimeT[F, A], fb: TimeT[F, B]): TimeT[
      F,
      Either[
        (Outcome[TimeT[F, *], E, A], Fiber[TimeT[F, *], E, B]),
        (Fiber[TimeT[F, *], E, A], Outcome[TimeT[F, *], E, B])]] =
      Kleisli { time =>
        val forkA = time.fork()
        val forkB = time.fork()

        // TODO this doesn't work (yet) because we need to force the "faster" effect to win the race, which right now isn't happening
        F.racePair(fa.run(forkA), fb.run(forkB)).map {
          case Left((oca, delegate)) =>
            time.now = forkA.now
            Left((oca.mapK(TimeT.liftK[F]), fiberize(forkB, delegate)))

          case Right((delegate, ocb)) =>
            time.now = forkB.now
            Right((fiberize(forkA, delegate), ocb.mapK(TimeT.liftK[F])))
        }
      }

    override def start[A](fa: TimeT[F, A]): TimeT[F, Fiber[TimeT[F, *], E, A]] =
      for {
        time <- Kleisli.ask[F, Time]
        forked = time.fork()
        delegate <- Kleisli.liftF(F.start(fa.run(forked)))
      } yield fiberize(forked, delegate)

    override val monotonic: TimeT[F, FiniteDuration] =
      Kleisli.ask[F, Time].map(_.now)

    override val realTime =
      pure(0.millis) // TODO is there anything better here?

    override def sleep(time: FiniteDuration): TimeT[F, Unit] =
      Kleisli.ask[F, Time].map(_.now += time) // what could go wrong*

    private[this] def fiberize[A](
        forked: Time,
        delegate: Fiber[F, E, A]): Fiber[TimeT[F, *], E, A] =
      new Fiber[TimeT[F, *], E, A] {

        val cancel =
          Kleisli.liftF(delegate.cancel)

        val join =
          Kleisli { outerTime =>
            delegate.join.map { back =>
              // if the forked time is less, then it means it completed first; if it's more, then we semantically block (by increasing "now")
              outerTime.now = outerTime.now.max(forked.now)
              back.mapK(Kleisli.liftK[F, Time])
            }
          }
      }
  }
}
