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

import cats.data.Kleisli
import cats.{Eq, Order}
import cats.laws.discipline.MiniInt
import cats.laws.discipline.arbitrary._
import cats.laws.discipline.eq._
import cats.effect.laws.AsyncTests
import cats.effect.testkit.{SyncTypeGenerators, TestContext}
import cats.implicits._

import org.scalacheck.Prop

import org.specs2.ScalaCheck
import org.specs2.scalacheck.Parameters

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class KleisliIOSpec
    extends IOPlatformSpecification
    with Discipline
    with ScalaCheck
    with BaseSpec {
  outer =>

  import SyncTypeGenerators._

  // we just need this because of the laws testing, since the prop runs can interfere with each other
  sequential

  implicit def kleisliEq[F[_], A, B](implicit ev: Eq[A => F[B]]): Eq[Kleisli[F, A, B]] =
    Eq.by[Kleisli[F, A, B], A => F[B]](_.run)

  implicit def ordKleisliIOFD(
      implicit ticker: Ticker): Order[Kleisli[IO, MiniInt, FiniteDuration]] =
    Order by { ioaO =>
      unsafeRun(ioaO.run(MiniInt.unsafeFromInt(0))).fold(None, _ => None, fa => fa)
    }

  implicit def execKleisli(sbool: Kleisli[IO, MiniInt, Boolean])(
      implicit ticker: Ticker): Prop =
    Prop(
      unsafeRun(sbool.run(MiniInt.unsafeFromInt(0))).fold(
        false,
        _ => false,
        bO => bO.fold(false)(identity)
      ))

  {
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "Kleisli[IO]",
      AsyncTests[Kleisli[IO, MiniInt, *]].async[Int, Int, Int](10.millis)
    )(Parameters(minTestsOk = 25))
  }

}
