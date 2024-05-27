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
import cats.data.{Chain, OptionT, WriterT}
import cats.effect.laws.AsyncTests
import cats.effect.syntax.all._
import cats.laws.discipline.arbitrary._
import cats.syntax.all._

import org.scalacheck.Prop
import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class WriterTIOSpec extends BaseSpec with Discipline {

  // we just need this because of the laws testing, since the prop runs can interfere with each other
  sequential

  "WriterT" should {
    "execute finalizers" in ticked { implicit ticker =>
      type F[A] = WriterT[IO, Chain[String], A]

      val test = for {
        gate <- Deferred[F, Unit]
        _ <- WriterT
          .tell[IO, Chain[String]](Chain.one("hello"))
          .guarantee(gate.complete(()).void)
          .start
        _ <- gate.get
      } yield ()

      test.run._2F must completeAs(())
    }

    "execute finalizers when doubly nested" in ticked { implicit ticker =>
      type F[A] = WriterT[OptionT[IO, *], Chain[String], A]

      val test = for {
        gate1 <- Deferred[F, Unit]
        gate2 <- Deferred[F, Unit]
        _ <- WriterT
          .tell[OptionT[IO, *], Chain[String]](Chain.one("hello"))
          .guarantee(gate1.complete(()).void)
          .start
        _ <- WriterT
          .liftF[OptionT[IO, *], Chain[String], Unit](OptionT.none)
          .guarantee(gate2.complete(()).void)
          .start
        _ <- gate1.get
        _ <- gate2.get
      } yield ()

      test.run._2F.value must completeAs(Some(()))
    }
  }

  implicit def ordWriterTIOFD(
      implicit ticker: Ticker): Order[WriterT[IO, Int, FiniteDuration]] =
    Order by { ioaO => unsafeRun(ioaO.run).fold(None, _ => None, fa => fa) }

  implicit def execWriterT[S](sbool: WriterT[IO, S, Boolean])(implicit ticker: Ticker): Prop =
    Prop(
      unsafeRun(sbool.run).fold(
        false,
        _ => false,
        pO => pO.fold(false)(p => p._2)
      )
    )

  {
    implicit val ticker = Ticker()

    checkAll(
      "WriterT[IO]",
      AsyncTests[WriterT[IO, Int, *]].async[Int, Int, Int](10.millis)
    )
  }

}
