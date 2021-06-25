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

import cats.data.{Chain, WriterT}
import cats.Order
import cats.laws.discipline.arbitrary._
import cats.effect.laws.AsyncTests
import cats.effect.syntax.all._
import cats.syntax.all._

import org.scalacheck.Prop

import org.specs2.ScalaCheck

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class WriterTIOSpec
    extends IOPlatformSpecification
    with Discipline
    with ScalaCheck
    with BaseSpec {
  outer =>

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
  }

  implicit def ordWriterTIOFD(
      implicit ticker: Ticker): Order[WriterT[IO, Int, FiniteDuration]] =
    Order by { ioaO => unsafeRun(ioaO.run).fold(None, _ => None, fa => fa) }

  //TODO remove once https://github.com/typelevel/cats/pull/3556 is released
  implicit def orderWriterT[F[_], S, A](
      implicit Ord: Order[F[(S, A)]]): Order[WriterT[F, S, A]] = Order.by(_.run)

  //TODO remove once https://github.com/typelevel/cats/pull/3556 is released
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
