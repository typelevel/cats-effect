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

import cats.effect.laws.SyncTests
import cats.kernel.laws.SerializableLaws.serializable
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline.AlignTests
import cats.laws.discipline.arbitrary._
import cats.syntax.all._

import org.scalacheck.Prop.forAll
import org.typelevel.discipline.specs2.mutable.Discipline

class SyncIOSpec extends BaseSpec with Discipline with SyncIOPlatformSpecification {

  "sync io monad" should {
    "produce a pure value when run" in {
      SyncIO.pure(42) must completeAsSync(42)
    }

    "suspend a side-effect without memoizing" in {
      var i = 42

      val ioa = SyncIO {
        i += 1
        i
      }

      ioa must completeAsSync(43)
      ioa must completeAsSync(44)
    }

    "capture errors in suspensions" in {
      case object TestException extends RuntimeException
      SyncIO(throw TestException) must failAsSync(TestException)
    }

    "map results to a new type" in {
      SyncIO.pure(42).map(_.toString) must completeAsSync("42")
    }

    "flatMap results sequencing both effects" in {
      var i = 0
      SyncIO.pure(42).flatMap(i2 => SyncIO { i = i2 }) must completeAsSync(())
      i mustEqual 42
    }

    "raiseError propagates out" in {
      case object TestException extends RuntimeException
      SyncIO.raiseError(TestException).void.flatMap(_ => SyncIO.pure(())) must failAsSync(
        TestException)
    }

    "errors can be handled" in {
      case object TestException extends RuntimeException
      SyncIO.raiseError[Unit](TestException).attempt must completeAsSync(Left(TestException))
    }

    "attempt is redeem with Left(_) for recover and Right(_) for map" in {
      forAll { (io: SyncIO[Int]) => io.attempt eqv io.redeem(Left(_), Right(_)) }
    }

    "attempt is flattened redeemWith" in {
      forAll {
        (io: SyncIO[Int], recover: Throwable => SyncIO[String], bind: Int => SyncIO[String]) =>
          io.attempt.flatMap(_.fold(recover, bind)) eqv io.redeemWith(recover, bind)
      }
    }

    "redeem is flattened redeemWith" in {
      forAll {
        (io: SyncIO[Int], recover: Throwable => SyncIO[String], bind: Int => SyncIO[String]) =>
          io.redeem(recover, bind).flatMap(identity) eqv io.redeemWith(recover, bind)
      }
    }

    "redeem subsumes handleError" in {
      forAll { (io: SyncIO[Int], recover: Throwable => Int) =>
        io.redeem(recover, identity) eqv io.handleError(recover)
      }
    }

    "redeemWith subsumes handleErrorWith" in {
      forAll { (io: SyncIO[Int], recover: Throwable => SyncIO[Int]) =>
        io.redeemWith(recover, SyncIO.pure) eqv io.handleErrorWith(recover)
      }
    }

    "redeem correctly recovers from errors" in {
      case object TestException extends RuntimeException
      SyncIO.raiseError[Unit](TestException).redeem(_ => 42, _ => 43) must completeAsSync(42)
    }

    "redeem maps successful results" in {
      SyncIO.unit.redeem(_ => 41, _ => 42) must completeAsSync(42)
    }

    "redeem catches exceptions thrown in recovery function" in {
      case object TestException extends RuntimeException
      case object ThrownException extends RuntimeException
      SyncIO
        .raiseError[Unit](TestException)
        .redeem(_ => throw ThrownException, _ => 42)
        .attempt must completeAsSync(Left(ThrownException))
    }

    "redeem catches exceptions thrown in map function" in {
      case object ThrownException extends RuntimeException
      SyncIO.unit.redeem(_ => 41, _ => throw ThrownException).attempt must completeAsSync(
        Left(ThrownException))
    }

    "redeemWith correctly recovers from errors" in {
      case object TestException extends RuntimeException
      SyncIO
        .raiseError[Unit](TestException)
        .redeemWith(_ => SyncIO.pure(42), _ => SyncIO.pure(43)) must completeAsSync(42)
    }

    "redeemWith binds successful results" in {
      SyncIO.unit.redeemWith(_ => SyncIO.pure(41), _ => SyncIO.pure(42)) must completeAsSync(42)
    }

    "redeemWith catches exceptions throw in recovery function" in {
      case object TestException extends RuntimeException
      case object ThrownException extends RuntimeException
      SyncIO
        .raiseError[Unit](TestException)
        .redeemWith(_ => throw ThrownException, _ => SyncIO.pure(42))
        .attempt must completeAsSync(Left(ThrownException))
    }

    "redeemWith catches exceptions thrown in bind function" in {
      case object ThrownException extends RuntimeException
      SyncIO
        .unit
        .redeem(_ => SyncIO.pure(41), _ => throw ThrownException)
        .attempt must completeAsSync(Left(ThrownException))
    }

    "evaluate 10,000 consecutive map continuations" in {
      def loop(i: Int): SyncIO[Unit] =
        if (i < 10000)
          SyncIO.unit.flatMap(_ => loop(i + 1)).map(u => u)
        else
          SyncIO.unit

      loop(0) must completeAsSync(())
    }

    "evaluate 10,000 consecutive handleErrorWith continuations" in {
      def loop(i: Int): SyncIO[Unit] =
        if (i < 10000)
          SyncIO.unit.flatMap(_ => loop(i + 1)).handleErrorWith(SyncIO.raiseError(_))
        else
          SyncIO.unit

      loop(0) must completeAsSync(())
    }

    "catch exceptions thrown in map functions" in {
      case object TestException extends RuntimeException
      SyncIO.unit.map(_ => (throw TestException): Unit).attempt must completeAsSync(
        Left(TestException))
    }

    "catch exceptions thrown in flatMap functions" in {
      case object TestException extends RuntimeException
      SyncIO.unit.flatMap(_ => (throw TestException): SyncIO[Unit]).attempt must completeAsSync(
        Left(TestException))
    }

    "catch exceptions thrown in handleErrorWith functions" in {
      case object TestException extends RuntimeException
      case object WrongException extends RuntimeException
      SyncIO
        .raiseError[Unit](WrongException)
        .handleErrorWith(_ => (throw TestException): SyncIO[Unit])
        .attempt must completeAsSync(Left(TestException))
    }

    "preserve monad right identity on uncancelable" in {
      val fa = MonadCancel[SyncIO].uncancelable(_ => MonadCancel[SyncIO].canceled)
      fa.flatMap(SyncIO.pure(_)) must completeAsSync(())
      fa must completeAsSync(())
    }

    "cancel flatMap continuations following a canceled uncancelable block" in {
      MonadCancel[SyncIO]
        .uncancelable(_ => MonadCancel[SyncIO].canceled)
        .flatMap(_ => SyncIO.pure(())) must completeAsSync(())
    }

    "cancel map continuations following a canceled uncancelable block" in {
      MonadCancel[SyncIO]
        .uncancelable(_ => MonadCancel[SyncIO].canceled)
        .map(_ => ()) must completeAsSync(())
    }

    "lift a SyncIO into IO" in realProp(arbitrarySyncIO[Int].arbitrary) { sio =>
      val io = sio.to[IO]

      for {
        res1 <- IO.delay(sio.unsafeRunSync()).attempt
        res2 <- io.attempt
        res <- IO.delay(res1 mustEqual res2)
      } yield res
    }

    "serialize" in {
      forAll { (io: SyncIO[Int]) => serializable(io) }
    }
  }

  {
    checkAll(
      "SyncIO",
      SyncTests[SyncIO].sync[Int, Int, Int]
    )
  }

  {
    checkAll(
      "SyncIO[Int]",
      MonoidTests[SyncIO[Int]].monoid
    )
  }

  {
    checkAll(
      "SyncIO",
      AlignTests[SyncIO].align[Int, Int, Int, Int]
    )
  }

  platformSpecs
}
