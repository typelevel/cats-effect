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

import cats.Order
import cats.data.{EitherT, OptionT}
import cats.effect.laws.AsyncTests
import cats.effect.syntax.all._
import cats.laws.discipline.arbitrary._
import cats.syntax.all._

import org.scalacheck.Prop
import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class EitherTIOSpec extends BaseSpec with Discipline {

  // we just need this because of the laws testing, since the prop runs can interfere with each other
  sequential

  "EitherT" should {
    "execute finalizers" in ticked { implicit ticker =>
      type F[A] = EitherT[IO, String, A]

      val test = for {
        gate1 <- Deferred[F, Unit]
        gate2 <- Deferred[F, Unit]
        _ <- EitherT.leftT[IO, Unit]("boom").guarantee(gate1.complete(()).void).start
        _ <- EitherT.rightT[IO, String](()).guarantee(gate2.complete(()).void).start
        _ <- gate1.get
        _ <- gate2.get
      } yield ()

      test.value must completeAs(Right(()))
    }

    "execute finalizers when doubly nested" in ticked { implicit ticker =>
      type F[A] = EitherT[OptionT[IO, *], String, A]

      val test = for {
        gate1 <- Deferred[F, Unit]
        gate2 <- Deferred[F, Unit]
        gate3 <- Deferred[F, Unit]
        _ <- EitherT
          .leftT[OptionT[IO, *], Unit]("boom")
          .guarantee(gate1.complete(()).void)
          .start
        _ <- EitherT.rightT[OptionT[IO, *], String](()).guarantee(gate2.complete(()).void).start
        _ <- EitherT.liftF(OptionT.none[IO, Unit]).guarantee(gate3.complete(()).void).start
        _ <- gate1.get
        _ <- gate2.get
        _ <- gate3.get
      } yield ()

      test.value.value must completeAs(Some(Right(())))
    }
  }

  implicit def ordEitherTIOFD(
      implicit ticker: Ticker): Order[EitherT[IO, Int, FiniteDuration]] =
    Order by { ioaO => unsafeRun(ioaO.value).fold(None, _ => None, fa => fa) }

  implicit def execEitherT(sbool: EitherT[IO, Int, Boolean])(implicit ticker: Ticker): Prop =
    Prop(
      unsafeRun(sbool.value).fold(
        false,
        _ => false,
        bO => bO.fold(false)(e => e.fold(_ => false, _ => true))
      ))

  {
    implicit val ticker = Ticker()

    checkAll(
      "EitherT[IO]",
      AsyncTests[EitherT[IO, Int, *]].async[Int, Int, Int](10.millis)
    ) /*(Parameters(seed = Some(Seed.fromBase64("XidlR_tu11X7_v51XojzZJsm6EaeU99RAEL9vzbkWBD=").get)))*/
  }

}
