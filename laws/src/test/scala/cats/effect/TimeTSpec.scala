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

package cats.effect

import cats.{~>, Applicative, ApplicativeError, Eq, FlatMap, Monad, Monoid, Order, Show}
import cats.data.{EitherK, Kleisli, StateT}
import cats.effect.laws.TemporalBracketTests
import cats.free.FreeT
import cats.implicits._
import cats.laws.discipline.arbitrary._

import coop.ThreadT

import pure._
import TimeT._

import org.specs2.ScalaCheck
import org.specs2.matcher.Matcher
import org.specs2.mutable._

import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

import java.util.concurrent.TimeUnit

private[effect] trait LowPriorityInstances {

  implicit def eqTimeT[F[_], A](implicit FA: Eq[F[A]]): Eq[TimeT[F, A]] =
    Eq.by(TimeT.run(_))
}

class TimeTSpec extends Specification with Discipline with ScalaCheck with LowPriorityInstances {
  import OutcomeGenerators._
  import PureConcGenerators._

  checkAll(
    "TimeT[PureConc, ?]",
    TemporalBracketTests[TimeT[PureConc[Int, ?], ?], Int].temporalBracket[Int, Int, Int](0.millis))

  implicit def exec(fb: TimeT[PureConc[Int, ?], Boolean]): Prop =
    Prop(pure.run(TimeT.run(fb)).fold(false, _ => false, _.getOrElse(false)))

  implicit def arbPositiveFiniteDuration: Arbitrary[FiniteDuration] = {
    import TimeUnit._

    val genTU = Gen.oneOf(NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS)

    Arbitrary {
      genTU flatMap { u =>
        Gen.posNum[Long].map(FiniteDuration(_, u))
      }
    }
  }

  implicit def orderTimeT[F[_], A](implicit FA: Order[F[A]]): Order[TimeT[F, A]] =
    Order.by(TimeT.run(_))

  implicit def pureConcOrder[E: Order, A: Order]: Order[PureConc[E, A]] =
    Order.by(pure.run(_))

  implicit def cogenTime: Cogen[Time] =
    Cogen[FiniteDuration].contramap(_.now)

  implicit def arbTime: Arbitrary[Time] =
    Arbitrary(Arbitrary.arbitrary[FiniteDuration].map(new Time(_)))

  implicit def cogenKleisli[F[_], R, A](implicit cg: Cogen[R => F[A]]): Cogen[Kleisli[F, R, A]] =
    cg.contramap(_.run)

  def beEqv[A: Eq: Show](expect: A): Matcher[A] = be_===[A](expect)

  def be_===[A: Eq: Show](expect: A): Matcher[A] = (result: A) =>
    (result === expect, s"${result.show} === ${expect.show}", s"${result.show} !== ${expect.show}")
}
