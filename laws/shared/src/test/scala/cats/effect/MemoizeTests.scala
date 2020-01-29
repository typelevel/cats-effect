/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats
package effect

import cats.implicits._
import cats.effect.concurrent.{Deferred, Ref}
import scala.concurrent.duration._
import scala.util.{Success}

import cats.effect.laws.discipline.arbitrary._
import cats.laws._
import cats.laws.discipline._

class MemoizeTests extends BaseTestsSuite {
  testAsync("Concurrent.memoize does not evaluates the effect if the inner `F[A]`isn't bound") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val timer = ec.timer[IO]

    val prog = for {
      ref <- Ref.of[IO, Int](0)
      action = ref.update(_ + 1)
      _ <- Concurrent.memoize(action)
      _ <- timer.sleep(100.millis)
      v <- ref.get
    } yield v

    val result = prog.unsafeToFuture()
    ec.tick(200.millis)
    result.value shouldBe Some(Success(0))
  }

  testAsync("Concurrent.memoize evalutes effect once if inner `F[A]` is bound twice") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val prog = for {
      ref <- Ref.of[IO, Int](0)
      action = ref.modify { s =>
        val ns = s + 1
        ns -> ns
      }
      memoized <- Concurrent.memoize(action)
      x <- memoized
      y <- memoized
      v <- ref.get
    } yield (x, y, v)

    val result = prog.unsafeToFuture()
    ec.tick()
    result.value shouldBe Some(Success((1, 1, 1)))
  }

  testAsync("Concurrent.memoize effect evaluates effect once if the inner `F[A]` is bound twice (race)") {
    implicit ec =>
      implicit val cs: ContextShift[IO] = ec.ioContextShift
      val timer = ec.timer[IO]

      val prog = for {
        ref <- Ref.of[IO, Int](0)
        action = ref.modify { s =>
          val ns = s + 1
          ns -> ns
        }
        memoized <- Concurrent.memoize(action)
        _ <- memoized.start
        x <- memoized
        _ <- timer.sleep(100.millis)
        v <- ref.get
      } yield x -> v

      val result = prog.unsafeToFuture()
      ec.tick(200.millis)
      result.value shouldBe Some(Success((1, 1)))
  }

  testAsync("Concurrent.memoize and then flatten is identity") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    check { (fa: IO[Int]) =>
      Concurrent.memoize(fa).flatten <-> fa
    }
  }

  testAsync("Memoized effects can be canceled when there are no other active subscribers (1)") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val prog = for {
      completed <- Ref[IO].of(false)
      action = IO.sleep(200.millis) >> completed.set(true)
      memoized <- Concurrent.memoize(action)
      fiber <- memoized.start
      _ <- IO.sleep(100.millis)
      _ <- fiber.cancel
      _ <- IO.sleep(300.millis)
      res <- completed.get
    } yield res

    val result = prog.unsafeToFuture()
    ec.tick(500.millis)
    result.value shouldBe Some(Success(false))
  }

  testAsync("Memoized effects can be canceled when there are no other active subscribers (2)") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val prog = for {
      completed <- Ref[IO].of(false)
      action = IO.sleep(300.millis) >> completed.set(true)
      memoized <- Concurrent.memoize(action)
      fiber1 <- memoized.start
      _ <- IO.sleep(100.millis)
      fiber2 <- memoized.start
      _ <- IO.sleep(100.millis)
      _ <- fiber2.cancel
      _ <- fiber1.cancel
      _ <- IO.sleep(400.millis)
      res <- completed.get
    } yield res

    val result = prog.unsafeToFuture()
    ec.tick(600.millis)
    result.value shouldBe Some(Success(false))
  }

  testAsync("Memoized effects can be canceled when there are no other active subscribers (3)") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val prog = for {
      completed <- Ref[IO].of(false)
      action = IO.sleep(300.millis) >> completed.set(true)
      memoized <- Concurrent.memoize(action)
      fiber1 <- memoized.start
      _ <- IO.sleep(100.millis)
      fiber2 <- memoized.start
      _ <- IO.sleep(100.millis)
      _ <- fiber1.cancel
      _ <- fiber2.cancel
      _ <- IO.sleep(400.millis)
      res <- completed.get
    } yield res

    val result = prog.unsafeToFuture()
    ec.tick(600.millis)
    result.value shouldBe Some(Success(false))
  }

  testAsync("Running a memoized effect after it was previously canceled reruns it") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val prog = for {
      started <- Ref[IO].of(0)
      completed <- Ref[IO].of(0)
      action = started.update(_ + 1) >> timer.sleep(200.millis) >> completed.update(_ + 1)
      memoized <- Concurrent.memoize(action)
      fiber <- memoized.start
      _ <- IO.sleep(100.millis)
      _ <- fiber.cancel
      _ <- memoized.timeout(1.second)
      v1 <- started.get
      v2 <- completed.get
    } yield v1 -> v2

    val result = prog.unsafeToFuture()
    ec.tick(500.millis)
    result.value shouldBe Some(Success((2, 1)))
  }

  testAsync("Attempting to cancel a memoized effect with active subscribers is a no-op") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val prog = for {
      condition <- Deferred[IO, Unit]
      action = IO.sleep(200.millis) >> condition.complete(())
      memoized <- Concurrent.memoize(action)
      fiber1 <- memoized.start
      _ <- IO.sleep(50.millis)
      fiber2 <- memoized.start
      _ <- IO.sleep(50.millis)
      _ <- fiber1.cancel
      _ <- fiber2.join // Make sure no exceptions are swallowed by start
      v <- condition.get.timeout(1.second).as(true)
    } yield v

    val result = prog.unsafeToFuture()
    ec.tick(500.millis)
    result.value shouldBe Some(Success(true))
  }
}
