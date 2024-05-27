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

package cats.effect.kernel.testkit

import cats.{Eq, Order}
import cats.effect.kernel.testkit.TimeT._
import cats.effect.kernel.testkit.pure.PureConc
import cats.effect.laws.GenTemporalTests
import cats.laws.discipline.arbitrary._

import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}
import org.specs2.mutable._
import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

import java.util.concurrent.TimeUnit

private[testkit] trait LowPriorityInstances {

  implicit def eqTimeT[F[_], A](implicit FA: Eq[F[A]]): Eq[TimeT[F, A]] =
    Eq.by(TimeT.run(_))
}

class TimeTSpec extends Specification with Discipline with LowPriorityInstances {

  import PureConcGenerators._
  import OutcomeGenerators._

  checkAll(
    "TimeT[PureConc, *]",
    GenTemporalTests[TimeT[PureConc[Int, *], *], Int].temporal[Int, Int, Int](0.millis))

  implicit def exec(fb: TimeT[PureConc[Int, *], Boolean]): Prop =
    Prop(pure.run(TimeT.run(fb)).fold(false, _ => false, _.getOrElse(false)))

  implicit def arbPositiveFiniteDuration: Arbitrary[FiniteDuration] = {
    import TimeUnit._

    val genTU =
      Gen.oneOf(NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS)

    Arbitrary {
      genTU flatMap { u => Gen.posNum[Long].map(FiniteDuration(_, u)) }
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
}
