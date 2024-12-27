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

  test("produce a pure value when run") {
    assertCompleteAsSync(SyncIO.pure(42), 42)
  }

  test("suspend a side-effect without memoizing") {
    var i = 42

    val ioa = SyncIO {
      i += 1
      i
    }

    assertCompleteAsSync(ioa, 43)
    assertCompleteAsSync(ioa, 44)
  }

  test("capture errors in suspensions") {
    case object TestException extends RuntimeException
    assertFailAsSync(SyncIO(throw TestException), TestException)
  }

  test("map results to a new type") {
    assertCompleteAsSync(SyncIO.pure(42).map(_.toString), "42")
  }

  test("flatMap results sequencing both effects") {
    var i = 0
    assertCompleteAsSync(SyncIO.pure(42).flatMap(i2 => SyncIO { i = i2 }), ())
    assertEquals(i, 42)
  }

  test("raiseError propagates out") {
    case object TestException extends RuntimeException
    assertFailAsSync(
      SyncIO.raiseError(TestException).void.flatMap(_ => SyncIO.pure(())),
      TestException)
  }

  test("errors can be handled") {
    case object TestException extends RuntimeException
    assertCompleteAsSync(SyncIO.raiseError[Unit](TestException).attempt, Left(TestException))
  }

  test("attempt is redeem with Left(_) for recover and Right(_) for map") {
    forAll { (io: SyncIO[Int]) => io.attempt eqv io.redeem(Left(_), Right(_)) }
  }

  test("attempt is flattened redeemWith") {
    forAll {
      (io: SyncIO[Int], recover: Throwable => SyncIO[String], bind: Int => SyncIO[String]) =>
        io.attempt.flatMap(_.fold(recover, bind)) eqv io.redeemWith(recover, bind)
    }
  }

  test("redeem is flattened redeemWith") {
    forAll {
      (io: SyncIO[Int], recover: Throwable => SyncIO[String], bind: Int => SyncIO[String]) =>
        io.redeem(recover, bind).flatMap(identity) eqv io.redeemWith(recover, bind)
    }
  }

  test("redeem subsumes handleError") {
    forAll { (io: SyncIO[Int], recover: Throwable => Int) =>
      io.redeem(recover, identity) eqv io.handleError(recover)
    }
  }

  test("redeemWith subsumes handleErrorWith") {
    forAll { (io: SyncIO[Int], recover: Throwable => SyncIO[Int]) =>
      io.redeemWith(recover, SyncIO.pure) eqv io.handleErrorWith(recover)
    }
  }

  test("redeem correctly recovers from errors") {
    case object TestException extends RuntimeException
    assertCompleteAsSync(SyncIO.raiseError[Unit](TestException).redeem(_ => 42, _ => 43), 42)
  }

  test("redeem maps successful results") {
    assertCompleteAsSync(SyncIO.unit.redeem(_ => 41, _ => 42), 42)
  }

  test("redeem catches exceptions thrown in recovery function") {
    case object TestException extends RuntimeException
    case object ThrownException extends RuntimeException
    assertCompleteAsSync(
      SyncIO
        .raiseError[Unit](TestException)
        .redeem(_ => throw ThrownException, _ => 42)
        .attempt,
      Left(ThrownException))
  }

  test("redeem catches exceptions thrown in map function") {
    case object ThrownException extends RuntimeException
    assertCompleteAsSync(
      SyncIO.unit.redeem(_ => 41, _ => throw ThrownException).attempt,
      Left(ThrownException))
  }

  test("redeemWith correctly recovers from errors") {
    case object TestException extends RuntimeException
    assertCompleteAsSync(
      SyncIO
        .raiseError[Unit](TestException)
        .redeemWith(_ => SyncIO.pure(42), _ => SyncIO.pure(43)),
      42)
  }

  test("redeemWith binds successful results") {
    assertCompleteAsSync(SyncIO.unit.redeemWith(_ => SyncIO.pure(41), _ => SyncIO.pure(42)), 42)
  }

  test("redeemWith catches exceptions throw in recovery function") {
    case object TestException extends RuntimeException
    case object ThrownException extends RuntimeException
    assertCompleteAsSync(
      SyncIO
        .raiseError[Unit](TestException)
        .redeemWith(_ => throw ThrownException, _ => SyncIO.pure(42))
        .attempt,
      Left(ThrownException))
  }

  test("redeemWith catches exceptions thrown in bind function") {
    case object ThrownException extends RuntimeException
    assertCompleteAsSync(
      SyncIO.unit.redeem(_ => SyncIO.pure(41), _ => throw ThrownException).attempt,
      Left(ThrownException))
  }

  test("evaluate 10,000 consecutive map continuations") {
    def loop(i: Int): SyncIO[Unit] =
      if (i < 10000)
        SyncIO.unit.flatMap(_ => loop(i + 1)).map(u => u)
      else
        SyncIO.unit

    assertCompleteAsSync(loop(0), ())
  }

  test("evaluate 10,000 consecutive handleErrorWith continuations") {
    def loop(i: Int): SyncIO[Unit] =
      if (i < 10000)
        SyncIO.unit.flatMap(_ => loop(i + 1)).handleErrorWith(SyncIO.raiseError(_))
      else
        SyncIO.unit

    assertCompleteAsSync(loop(0), ())
  }

  test("catch exceptions thrown in map functions") {
    case object TestException extends RuntimeException
    assertCompleteAsSync(
      SyncIO.unit.map(_ => (throw TestException): Unit).attempt,
      Left(TestException))
  }

  test("catch exceptions thrown in flatMap functions") {
    case object TestException extends RuntimeException
    assertCompleteAsSync(
      SyncIO.unit.flatMap(_ => (throw TestException): SyncIO[Unit]).attempt,
      Left(TestException))
  }

  test("catch exceptions thrown in handleErrorWith functions") {
    case object TestException extends RuntimeException
    case object WrongException extends RuntimeException
    assertCompleteAsSync(
      SyncIO
        .raiseError[Unit](WrongException)
        .handleErrorWith(_ => (throw TestException): SyncIO[Unit])
        .attempt,
      Left(TestException))
  }

  test("preserve monad right identity on uncancelable") {
    val fa = MonadCancel[SyncIO].uncancelable(_ => MonadCancel[SyncIO].canceled)
    assertCompleteAsSync(fa.flatMap(SyncIO.pure(_)), ())
    assertCompleteAsSync(fa, ())
  }

  test("cancel flatMap continuations following a canceled uncancelable block") {
    assertCompleteAsSync(
      MonadCancel[SyncIO]
        .uncancelable(_ => MonadCancel[SyncIO].canceled)
        .flatMap(_ => SyncIO.pure(())),
      ())
  }

  test("cancel map continuations following a canceled uncancelable block") {
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

  test("serialize") {
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
