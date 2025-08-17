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

import cats.effect.laws.SyncTests
import cats.kernel.laws.SerializableLaws.serializable
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline.AlignTests
import cats.laws.discipline.arbitrary._
import cats.syntax.all._

import org.scalacheck.Prop.forAll

import munit.DisciplineSuite

class SyncIOSuite extends BaseSuite with DisciplineSuite with SyncIOPlatformSuite {

  testUnit("produce a pure value when run") {
    assertCompleteAsSync(SyncIO.pure(42), 42)
  }

  testUnit("suspend a side-effect without memoizing") {
    var i = 42

    val ioa = SyncIO {
      i += 1
      i
    }

    assertCompleteAsSync(ioa, 43)
    assertCompleteAsSync(ioa, 44)
  }

  testUnit("capture errors in suspensions") {
    case object TestException extends RuntimeException
    assertFailAsSync(SyncIO(throw TestException), TestException)
  }

  testUnit("map results to a new type") {
    assertCompleteAsSync(SyncIO.pure(42).map(_.toString), "42")
  }

  testUnit("flatMap results sequencing both effects") {
    var i = 0
    assertCompleteAsSync(SyncIO.pure(42).flatMap(i2 => SyncIO { i = i2 }), ())
    assertEquals(i, 42)
  }

  testUnit("raiseError propagates out") {
    case object TestException extends RuntimeException
    assertFailAsSync(
      SyncIO.raiseError(TestException).void.flatMap(_ => SyncIO.pure(())),
      TestException)
  }

  testUnit("errors can be handled") {
    case object TestException extends RuntimeException
    assertCompleteAsSync(SyncIO.raiseError[Unit](TestException).attempt, Left(TestException))
  }

  property("attempt is redeem with Left(_) for recover and Right(_) for map") {
    forAll { (io: SyncIO[Int]) => assertEqv(io.attempt, io.redeem(Left(_), Right(_))) }
  }

  property("attempt is flattened redeemWith") {
    forAll {
      (io: SyncIO[Int], recover: Throwable => SyncIO[String], bind: Int => SyncIO[String]) =>
        assertEqv(io.attempt.flatMap(_.fold(recover, bind)), io.redeemWith(recover, bind))
    }
  }

  property("redeem is flattened redeemWith") {
    forAll {
      (io: SyncIO[Int], recover: Throwable => SyncIO[String], bind: Int => SyncIO[String]) =>
        assertEqv(io.redeem(recover, bind).flatMap(identity), io.redeemWith(recover, bind))
    }
  }

  property("redeem subsumes handleError") {
    forAll { (io: SyncIO[Int], recover: Throwable => Int) =>
      assertEqv(io.redeem(recover, identity), io.handleError(recover))
    }
  }

  property("redeemWith subsumes handleErrorWith") {
    forAll { (io: SyncIO[Int], recover: Throwable => SyncIO[Int]) =>
      assertEqv(io.redeemWith(recover, SyncIO.pure), io.handleErrorWith(recover))
    }
  }

  property("redeem correctly recovers from errors") {
    case object TestException extends RuntimeException
    assertCompleteAsSync(SyncIO.raiseError[Unit](TestException).redeem(_ => 42, _ => 43), 42)
  }

  testUnit("redeem maps successful results") {
    assertCompleteAsSync(SyncIO.unit.redeem(_ => 41, _ => 42), 42)
  }

  testUnit("redeem catches exceptions thrown in recovery function") {
    case object TestException extends RuntimeException
    case object ThrownException extends RuntimeException
    assertCompleteAsSync(
      SyncIO
        .raiseError[Unit](TestException)
        .redeem(_ => throw ThrownException, _ => 42)
        .attempt,
      Left(ThrownException))
  }

  testUnit("redeem catches exceptions thrown in map function") {
    case object ThrownException extends RuntimeException
    assertCompleteAsSync(
      SyncIO.unit.redeem(_ => 41, _ => throw ThrownException).attempt,
      Left(ThrownException))
  }

  testUnit("redeemWith correctly recovers from errors") {
    case object TestException extends RuntimeException
    assertCompleteAsSync(
      SyncIO
        .raiseError[Unit](TestException)
        .redeemWith(_ => SyncIO.pure(42), _ => SyncIO.pure(43)),
      42)
  }

  testUnit("redeemWith binds successful results") {
    assertCompleteAsSync(SyncIO.unit.redeemWith(_ => SyncIO.pure(41), _ => SyncIO.pure(42)), 42)
  }

  testUnit("redeemWith catches exceptions throw in recovery function") {
    case object TestException extends RuntimeException
    case object ThrownException extends RuntimeException
    assertCompleteAsSync(
      SyncIO
        .raiseError[Unit](TestException)
        .redeemWith(_ => throw ThrownException, _ => SyncIO.pure(42))
        .attempt,
      Left(ThrownException))
  }

  testUnit("redeemWith catches exceptions thrown in bind function") {
    case object ThrownException extends RuntimeException
    assertCompleteAsSync(
      SyncIO.unit.redeem(_ => SyncIO.pure(41), _ => throw ThrownException).attempt,
      Left(ThrownException))
  }

  testUnit("evaluate 10,000 consecutive map continuations") {
    def loop(i: Int): SyncIO[Unit] =
      if (i < 10000)
        SyncIO.unit.flatMap(_ => loop(i + 1)).map(u => u)
      else
        SyncIO.unit

    assertCompleteAsSync(loop(0), ())
  }

  testUnit("evaluate 10,000 consecutive handleErrorWith continuations") {
    def loop(i: Int): SyncIO[Unit] =
      if (i < 10000)
        SyncIO.unit.flatMap(_ => loop(i + 1)).handleErrorWith(SyncIO.raiseError(_))
      else
        SyncIO.unit

    assertCompleteAsSync(loop(0), ())
  }

  testUnit("catch exceptions thrown in map functions") {
    case object TestException extends RuntimeException
    assertCompleteAsSync(
      SyncIO.unit.map(_ => (throw TestException): Unit).attempt,
      Left(TestException))
  }

  testUnit("catch exceptions thrown in flatMap functions") {
    case object TestException extends RuntimeException
    assertCompleteAsSync(
      SyncIO.unit.flatMap(_ => (throw TestException): SyncIO[Unit]).attempt,
      Left(TestException))
  }

  testUnit("catch exceptions thrown in handleErrorWith functions") {
    case object TestException extends RuntimeException
    case object WrongException extends RuntimeException
    assertCompleteAsSync(
      SyncIO
        .raiseError[Unit](WrongException)
        .handleErrorWith(_ => (throw TestException): SyncIO[Unit])
        .attempt,
      Left(TestException))
  }

  testUnit("preserve monad right identity on uncancelable") {
    val fa = MonadCancel[SyncIO].uncancelable(_ => MonadCancel[SyncIO].canceled)
    assertCompleteAsSync(fa.flatMap(SyncIO.pure(_)), ())
    assertCompleteAsSync(fa, ())
  }

  testUnit("cancel flatMap continuations following a canceled uncancelable block") {
    assertCompleteAsSync(
      MonadCancel[SyncIO]
        .uncancelable(_ => MonadCancel[SyncIO].canceled)
        .flatMap(_ => SyncIO.pure(())),
      ())
  }

  testUnit("cancel map continuations following a canceled uncancelable block") {
    assertCompleteAsSync(
      MonadCancel[SyncIO].uncancelable(_ => MonadCancel[SyncIO].canceled).map(_ => ()),
      ())
  }

  realProp("lift a SyncIO into IO", arbitrarySyncIO[Int].arbitrary) { sio =>
    val io = sio.to[IO]

    for {
      res1 <- IO.delay(sio.unsafeRunSync()).attempt
      res2 <- io.attempt
      res <- IO.delay(assertEquals(res1, res2))
    } yield res
  }

  property("serialize") {
    forAll { (io: SyncIO[Int]) => serializable(io) }
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

  platformTests()
}
