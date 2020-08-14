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

import cats.{Order, Show}
import cats.data.WriterT
import cats.laws.discipline.arbitrary._
import cats.implicits._
import cats.effect.laws.TemporalTests
import cats.effect.testkit._
import cats.effect.testkit.TimeT._
import cats.effect.testkit.{pure, PureConcGenerators}, pure._

// import org.scalacheck.rng.Seed
import org.scalacheck.util.Pretty
import org.scalacheck.Prop

import org.specs2.ScalaCheck
// import org.specs2.scalacheck.Parameters
import org.specs2.mutable._

import scala.concurrent.duration._

import org.typelevel.discipline.specs2.mutable.Discipline

class WriterTPureConcSpec extends Specification with Discipline with ScalaCheck {
  import PureConcGenerators._

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

  //TODO remove once https://github.com/typelevel/cats/pull/3556 is released
  implicit def orderWriterT[F[_], S, A](
      implicit Ord: Order[F[(S, A)]]): Order[WriterT[F, S, A]] = Order.by(_.run)

  //TODO remove once https://github.com/typelevel/cats/pull/3556 is released
  implicit def execWriterT[S](sbool: WriterT[TimeT[PureConc[Int, *], *], S, Boolean]): Prop =
    Prop(
      pure
        .run(TimeT.run(sbool.run))
        .fold(
          false,
          _ => false,
          pO => pO.fold(false)(p => p._2)
        )
    )

  checkAll(
    "WriterT[PureConc]",
    TemporalTests[WriterT[TimeT[PureConc[Int, *], *], Int, *], Int]
      .temporal[Int, Int, Int](10.millis)
    // ) (Parameters(seed = Some(Seed.fromBase64("IDF0zP9Be_vlUEA4wfnKjd8gE8RNQ6tj-BvSVAUp86J=").get)))
  )
}
