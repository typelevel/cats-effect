/*
 * Copyright 2020-2025 Typelevel
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

import cats.{Eq, Order}
import cats.data.{Kleisli, OptionT}
import cats.effect.laws.AsyncTests
import cats.effect.syntax.all._
import cats.laws.discipline.MiniInt
import cats.laws.discipline.arbitrary._
import cats.laws.discipline.eq._
import cats.syntax.all._

import org.scalacheck.{Cogen, Prop, Test}

import scala.concurrent.duration._

import munit.DisciplineSuite

class KleisliIOSuite extends BaseSuite with DisciplineSuite {

  ticked("should be stack safe in long traverse chains") { implicit ticker =>
    val N = 10000

    val test = for {
      ref <- Ref[IO].of(0)
      _ <- List.fill(N)(0).traverse_(_ => Kleisli.liftF(ref.update(_ + 1))).run("Go...")
      v <- ref.get
    } yield v

    assertCompleteAs(test, N)
  }

  ticked("should be stack safe in long parTraverse chains") { implicit ticker =>
    val N = 10000

    val test = for {
      ref <- Ref[IO].of(0)
      _ <- List.fill(N)(0).parTraverse_(_ => Kleisli.liftF(ref.update(_ + 1))).run("Go...")
      v <- ref.get
    } yield v

    assertCompleteAs(test, N)
  }

  ticked("execute finalizers") { implicit ticker =>
    type F[A] = Kleisli[IO, String, A]

    val test = for {
      gate <- Deferred[F, Unit]
      _ <- Kleisli.ask[IO, String].guarantee(gate.complete(()).void).start
      _ <- gate.get
    } yield ()

    assertCompleteAs(test.run("kleisli"), ())
  }

  ticked("execute finalizers when doubly nested") { implicit ticker =>
    type F[A] = Kleisli[OptionT[IO, *], String, A]

    val test = for {
      gate1 <- Deferred[F, Unit]
      gate2 <- Deferred[F, Unit]
      _ <- Kleisli.ask[OptionT[IO, *], String].guarantee(gate1.complete(()).void).start
      _ <- Kleisli
        .liftF[OptionT[IO, *], String, Unit](OptionT.none[IO, Unit])
        .guarantee(gate2.complete(()).void)
        .start
      _ <- gate1.get
      _ <- gate2.get
    } yield ()

    assertCompleteAs(test.run("kleisli").value, Some(()))
  }

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

  implicit def cogenForKleisli[F[_], A, B](
      implicit F: Cogen[A => F[B]]): Cogen[Kleisli[F, A, B]] =
    F.contramap(_.run)

  override protected def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(25)

  {
    implicit val ticker = Ticker()

    checkAll(
      "Kleisli[IO]",
      AsyncTests[Kleisli[IO, MiniInt, *]].async[Int, Int, Int](10.millis)
    )
  }

}
