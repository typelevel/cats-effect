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
package laws

import cats.Order
import cats.data.Kleisli
import cats.effect.testkit.{pure, PureConcGenerators, Time, TimeT}, pure._, TimeT._
import cats.laws.discipline.{arbitrary, MiniInt}

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
    with BaseSpec
    with LowPriorityKleisliInstances {
  import PureConcGenerators._
  import arbitrary.{catsLawsArbitraryForKleisli => _, _}

  //This is highly dubious
  implicit def orderKleisli[F[_], A](implicit Ord: Order[F[A]]): Order[Kleisli[F, MiniInt, A]] =
    Order.by(_.run(MiniInt.unsafeFromInt(0)))

  //This is highly dubious
  implicit def exec(sbool: Kleisli[TimeT[PureConc[Int, *], *], MiniInt, Boolean]): Prop =
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
  )(Parameters(minTestsOk = 25))
}

//Push the priority of Kleisli instances down so we can explicitly summon more
//specific instances to help 2.12 out - I think Kleisli[TimeT[PureConc[Int, *], *], MiniInt, *]
//involves about 4 nested Kleisli's so the compiler just gives up
private[laws] trait LowPriorityKleisliInstances {
  implicit def catsLawsArbitraryForKleisli[F[_], A, B](
      implicit AA: Arbitrary[A],
      CA: Cogen[A],
      F: Arbitrary[F[B]]): Arbitrary[Kleisli[F, A, B]] =
    arbitrary.catsLawsArbitraryForKleisli[F, A, B]
}
