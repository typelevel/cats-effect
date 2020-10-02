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

import cats.{Eq, Order, Show}
import cats.data.Kleisli
//import cats.laws.discipline.{AlignTests, ParallelTests}
import cats.laws.discipline.arbitrary
import cats.laws.discipline.MiniInt
import cats.syntax.all._
//import cats.effect.kernel.ParallelF
import cats.effect.laws.GenTemporalTests
import cats.effect.testkit.{pure, PureConcGenerators}, pure._
import cats.effect.testkit._
import cats.effect.testkit.TimeT._

// import org.scalacheck.rng.Seed
import org.scalacheck.util.Pretty
import org.scalacheck.{Arbitrary, Cogen, Prop}

import org.specs2.ScalaCheck
import org.specs2.scalacheck.Parameters
import org.specs2.mutable._

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class KleisliPureConcSpec
    extends Specification
    with Discipline
    with ScalaCheck
    with LowPriorityInstances {
  import PureConcGenerators._
  import arbitrary.{catsLawsArbitraryForKleisli => _, _}
//  import ParallelFGenerators._

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

  implicit def kleisliEq[F[_], A, B](implicit ev: Eq[A => F[B]]): Eq[Kleisli[F, A, B]] =
    Eq.by[Kleisli[F, A, B], A => F[B]](_.run)

  //This is highly dubious
  implicit def orderKleisli[F[_], A](implicit Ord: Order[F[A]]): Order[Kleisli[F, MiniInt, A]] =
    Order.by(_.run(MiniInt.unsafeFromInt(0)))

  //This is highly dubious
  implicit def execKleisli(sbool: Kleisli[TimeT[PureConc[Int, *], *], MiniInt, Boolean]): Prop =
    Prop(
      pure
        .run(TimeT.run(sbool.run(MiniInt.unsafeFromInt(0))))
        .fold(false, _ => false, bO => bO.fold(false)(b => b))
    )

  implicit def help_scala_2_12_a_new_hope_of_compilation[A: Arbitrary: Cogen]
      : Arbitrary[PureConc[Int, A]] =
    arbitraryPureConc[Int, A]

  implicit def help_scala_2_12_diverging_implicits_strike_back[A: Arbitrary: Cogen]
      : Arbitrary[TimeT[PureConc[Int, *], A]] =
    catsLawsArbitraryForKleisli[PureConc[Int, *], Time, A]

  implicit def help_scala_2_12_return_of_the_successful_compilation[A: Arbitrary: Cogen]
      : Arbitrary[Kleisli[TimeT[PureConc[Int, *], *], MiniInt, A]] =
    catsLawsArbitraryForKleisli[TimeT[PureConc[Int, *], *], MiniInt, A]

  checkAll(
    "Kleisli[PureConc]",
    GenTemporalTests[Kleisli[TimeT[PureConc[Int, *], *], MiniInt, *], Int]
      .temporal[Int, Int, Int](10.millis)
    // we need to bound this a little tighter because these tests take FOREVER
  )(Parameters(minTestsOk =
    25 /*, seed = Some(Seed.fromBase64("IDF0zP9Be_vlUEA4wfnKjd8gE8RNQ6tj-BvSVAUp86J=").get)*/ ))
}

//Push the priority of Kleisli instances down so we can explicitly summon more
//specific instances to help 2.12 out - I think Kleisli[TimeT[PureConc[Int, *], *], MiniInt, *]
//involves about 4 nested Kleisli's so the compiler just gives up
trait LowPriorityInstances {
  implicit def catsLawsArbitraryForKleisli[F[_], A, B](
      implicit AA: Arbitrary[A],
      CA: Cogen[A],
      F: Arbitrary[F[B]]): Arbitrary[Kleisli[F, A, B]] =
    arbitrary.catsLawsArbitraryForKleisli[F, A, B]
}
