/*
 * Copyright 2020-2021 Typelevel
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

import cats.data.OptionT
import cats.Order
import cats.laws.discipline.arbitrary._
import cats.effect.laws.AsyncTests
import cats.effect.syntax.all._
import cats.syntax.all._

import org.scalacheck.Prop
// import org.scalacheck.rng.Seed

import org.specs2.ScalaCheck
// import org.specs2.scalacheck.Parameters

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class OptionTIOSpec
    extends IOPlatformSpecification
    with Discipline
    with ScalaCheck
    with BaseSpec {
  outer =>

  // we just need this because of the laws testing, since the prop runs can interfere with each other
  sequential

  "OptionT" should {
    "execute finalizers for None" in ticked { implicit ticker =>
      type F[A] = OptionT[IO, A]

      val test = for {
        gate <- Deferred[F, Unit]
        _ <- OptionT.none[IO, Unit].guarantee(gate.complete(()).void).start
        _ <- gate.get
      } yield ()

      test.value must completeAs(Some(()))
    }
  }

  implicit def ordOptionTIOFD(implicit ticker: Ticker): Order[OptionT[IO, FiniteDuration]] =
    Order by { ioaO => unsafeRun(ioaO.value).fold(None, _ => None, fa => fa) }

  implicit def execOptionT(sbool: OptionT[IO, Boolean])(implicit ticker: Ticker): Prop =
    Prop(
      unsafeRun(sbool.value).fold(
        false,
        _ => false,
        bO => bO.flatten.fold(false)(b => b)
      ))

  {
    implicit val ticker = Ticker()

    checkAll(
      "OptionT[IO]",
      AsyncTests[OptionT[IO, *]].async[Int, Int, Int](10.millis)
    ) /*(Parameters(seed = Some(Seed.fromBase64("xuJLKQlO7U9WUCzxlh--IB-5ppu1VpQkCFAmUX3tIrM=").get)))*/
  }

}
