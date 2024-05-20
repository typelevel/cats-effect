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

import cats.{Eq, FlatMap}
import cats.data.{OptionT, StateT}
import cats.effect.laws.SyncTests
import cats.effect.syntax.all._
import cats.laws.discipline.MiniInt
import cats.laws.discipline.arbitrary._
import cats.laws.discipline.eq._

import org.scalacheck.Prop
import org.specs2.scalacheck.Parameters
import org.typelevel.discipline.specs2.mutable.Discipline

class StateTIOSpec extends BaseSpec with Discipline {

  sequential

  "StateT" should {
    "execute finalizers" in ticked { implicit ticker =>
      var guaranteed = false

      val test = for {
        _ <- StateT.set[IO, String]("state").guarantee(StateT.liftF(IO { guaranteed = true }))
        g <- StateT.liftF(IO(guaranteed))
      } yield g

      test.runA("") must completeAs(true)
    }

    "execute finalizers when doubly nested" in ticked { implicit ticker =>
      var guaranteed = false

      val test = for {
        _ <- StateT
          .set[OptionT[IO, *], String]("state")
          .guarantee(StateT.liftF(OptionT.liftF(IO { guaranteed = true })))
        g <- StateT.liftF(OptionT.liftF(IO(guaranteed)))
      } yield g

      test.runA("").value must completeAs(Some(true))
    }
  }

  implicit def stateTEq[F[_]: FlatMap, S, A](
      implicit ev: Eq[S => F[(S, A)]]): Eq[StateT[F, S, A]] =
    Eq.by[StateT[F, S, A], S => F[(S, A)]](_.run)

  implicit def execStateT(sbool: StateT[IO, MiniInt, Boolean])(implicit ticker: Ticker): Prop =
    Prop(
      unsafeRun(sbool.run(MiniInt.unsafeFromInt(0))).fold(
        false,
        _ => false,
        pO => pO.fold(false)(p => p._2)
      )
    )

  {
    implicit val ticker = Ticker()

    checkAll("StateT[IO]", SyncTests[StateT[IO, MiniInt, *]].sync[Int, Int, Int])(
      Parameters(minTestsOk = 25))
  }
}
