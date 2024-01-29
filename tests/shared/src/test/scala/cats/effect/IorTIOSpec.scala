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
import cats.data.{Ior, IorT, OptionT}
import cats.effect.laws.AsyncTests
import cats.effect.syntax.all._
import cats.laws.discipline.arbitrary._
import cats.syntax.all._

import org.scalacheck.Prop
import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class IorTIOSpec extends BaseSpec with Discipline {

  // we just need this because of the laws testing, since the prop runs can interfere with each other
  sequential

  "IorT" should {
    "execute finalizers" in ticked { implicit ticker =>
      type F[A] = IorT[IO, String, A]

      val test = for {
        gate1 <- Deferred[F, Unit]
        gate2 <- Deferred[F, Unit]
        gate3 <- Deferred[F, Unit]
        _ <- IorT.leftT[IO, Unit]("boom").guarantee(gate1.complete(()).void).start
        _ <- IorT.bothT[IO]("boom", ()).guarantee(gate2.complete(()).void).start
        _ <- IorT.rightT[IO, String](()).guarantee(gate3.complete(()).void).start
        _ <- gate1.get
        _ <- gate2.get
        _ <- gate3.get
      } yield ()

      test.value must completeAs(Ior.right(()))
    }

    "execute finalizers when doubly nested" in ticked { implicit ticker =>
      type F[A] = IorT[OptionT[IO, *], String, A]

      val test = for {
        gate1 <- Deferred[F, Unit]
        gate2 <- Deferred[F, Unit]
        gate3 <- Deferred[F, Unit]
        gate4 <- Deferred[F, Unit]
        _ <- IorT.leftT[OptionT[IO, *], Unit]("boom").guarantee(gate1.complete(()).void).start
        _ <- IorT.bothT[OptionT[IO, *]]("boom", ()).guarantee(gate2.complete(()).void).start
        _ <- IorT.rightT[OptionT[IO, *], String](()).guarantee(gate3.complete(()).void).start
        _ <- IorT.liftF(OptionT.none[IO, Unit]).guarantee(gate4.complete(()).void).start
        _ <- gate1.get
        _ <- gate2.get
        _ <- gate3.get
        _ <- gate4.get
      } yield ()

      test.value.value must completeAs(Some(Ior.right(())))
    }
  }

  implicit def ordIorTIOFD(implicit ticker: Ticker): Order[IorT[IO, Int, FiniteDuration]] =
    Order by { ioaO => unsafeRun(ioaO.value).fold(None, _ => None, fa => fa) }

  implicit def execIorT(sbool: IorT[IO, Int, Boolean])(implicit ticker: Ticker): Prop =
    Prop(
      unsafeRun(sbool.value).fold(
        false,
        _ => false,
        iO => iO.fold(false)(i => i.fold(_ => false, _ => true, (_, _) => false)))
    )

  {
    implicit val ticker = Ticker()

    checkAll(
      "IorT[IO]",
      AsyncTests[IorT[IO, Int, *]].async[Int, Int, Int](10.millis)
    )
  }

}
