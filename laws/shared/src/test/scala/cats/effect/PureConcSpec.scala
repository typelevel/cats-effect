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

import cats.{Eq, Show}
import cats.data.{EitherT, IorT, Kleisli, OptionT, WriterT}
//import cats.laws.discipline.{AlignTests, ParallelTests}
import cats.laws.discipline.arbitrary._
import cats.laws.discipline.{eq, MiniInt}; import eq._
import cats.implicits._
//import cats.effect.kernel.ParallelF
import cats.effect.laws.TemporalTests
import cats.effect.testkit.{pure, PureConcGenerators}, pure._

// import org.scalacheck.rng.Seed
import org.scalacheck.util.Pretty

import org.specs2.ScalaCheck
// import org.specs2.scalacheck.Parameters
import org.specs2.mutable._

import org.typelevel.discipline.specs2.mutable.Discipline

class PureConcSpec extends Specification with Discipline with ScalaCheck {
  import PureConcGenerators._
//  import ParallelFGenerators._

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

  implicit def kleisliEq[F[_], A, B](implicit ev: Eq[A => F[B]]): Eq[Kleisli[F, A, B]] =
    Eq.by[Kleisli[F, A, B], A => F[B]](_.run)

  checkAll(
    "PureConc",
    TemporalTests[PureConc[Int, *], Int].concurrent[Int, Int, Int]
  ) /*(Parameters(seed = Some(Seed.fromBase64("OjD4TDlPxwCr-K-gZb-xyBOGeWMKx210V24VVhsJBLI=").get)))*/

  checkAll(
    "OptionT[PureConc]",
    TemporalTests[OptionT[PureConc[Int, *], *], Int].concurrent[Int, Int, Int]
    // ) (Parameters(seed = Some(Seed.fromBase64("IDF0zP9Be_vlUEA4wfnKjd8gE8RNQ6tj-BvSVAUp86J=").get)))
  )

  checkAll(
    "EitherT[PureConc]",
    TemporalTests[EitherT[PureConc[Int, *], Int, *], Int].concurrent[Int, Int, Int]
    // ) (Parameters(seed = Some(Seed.fromBase64("IDF0zP9Be_vlUEA4wfnKjd8gE8RNQ6tj-BvSVAUp86J=").get)))
  )

  checkAll(
    "IorT[PureConc]",
    TemporalTests[IorT[PureConc[Int, *], Int, *], Int].concurrent[Int, Int, Int]
    // ) (Parameters(seed = Some(Seed.fromBase64("IDF0zP9Be_vlUEA4wfnKjd8gE8RNQ6tj-BvSVAUp86J=").get)))
  )

  checkAll(
    "Kleisli[PureConc]",
    TemporalTests[Kleisli[PureConc[Int, *], MiniInt, *], Int].concurrent[Int, Int, Int]
    // ) (Parameters(seed = Some(Seed.fromBase64("IDF0zP9Be_vlUEA4wfnKjd8gE8RNQ6tj-BvSVAUp86J=").get)))
  )

  checkAll(
    "WriterT[PureConc]",
    TemporalTests[WriterT[PureConc[Int, *], Int, *], Int].concurrent[Int, Int, Int]
    // ) (Parameters(seed = Some(Seed.fromBase64("IDF0zP9Be_vlUEA4wfnKjd8gE8RNQ6tj-BvSVAUp86J=").get)))
  )

//  checkAll("PureConc", ParallelTests[PureConc[Int, *]].parallel[Int, Int])

//  checkAll(
//    "ParallelF[PureConc]",
//    AlignTests[ParallelF[PureConc[Int, *], *]].align[Int, Int, Int, Int])
}
