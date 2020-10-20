/*
 * Copyright 2020 Typelevel
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

import cats.syntax.all._

import org.scalacheck.Prop, Prop.forAll

import org.specs2.ScalaCheck

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._
import scala.util.Success

class MemoizeSpec extends BaseSpec with Discipline with ScalaCheck {

  sequential

  "Concurrent.memoize" >> {

    "Concurrent.memoize does not evaluates the effect if the inner `F[A]` isn't bound" in ticked {
      implicit ticker =>
        val op = for {
          ref <- Ref.of[IO, Int](0)
          action = ref.update(_ + 1)
          _ <- Concurrent[IO].memoize(action)
          v <- ref.get
        } yield v

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll()

        result.value mustEqual Some(Success(0))
    }

    "Concurrent.memoize evalutes effect once if inner `F[A]` is bound twice" in ticked {
      implicit ticker =>
        val op = for {
          ref <- Ref.of[IO, Int](0)
          action = ref.modify { s =>
            val ns = s + 1
            ns -> ns
          }
          memoized <- Concurrent[IO].memoize(action)
          x <- memoized
          y <- memoized
          v <- ref.get
        } yield (x, y, v)

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll()

        result.value mustEqual Some(Success((1, 1, 1)))
    }

    "Concurrent.memoize effect evaluates effect once if the inner `F[A]` is bound twice (race)" in ticked {
      implicit ticker =>
        val op = for {
          ref <- Ref.of[IO, Int](0)
          action = ref.modify { s =>
            val ns = s + 1
            ns -> ns
          }
          memoized <- Concurrent[IO].memoize(action)
          _ <- memoized.start
          x <- memoized
          _ <- IO(ticker.ctx.tickAll())
          v <- ref.get
        } yield x -> v

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll()

        result.value mustEqual Some(Success((1, 1)))
    }

    "Concurrent.memoize and then flatten is identity" in ticked { implicit ticker =>
      forAll { (fa: IO[Int]) => Concurrent[IO].memoize(fa).flatten eqv fa }
    }

    "Memoized effects can be canceled when there are no other active subscribers (1)" in ticked {
      implicit ticker =>
        val op = for {
          completed <- Ref[IO].of(false)
          action = IO.sleep(200.millis) >> completed.set(true)
          memoized <- Concurrent[IO].memoize(action)
          fiber <- memoized.start
          _ <- IO.sleep(100.millis)
          _ <- fiber.cancel
          _ <- IO.sleep(300.millis)
          res <- completed.get
        } yield res

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll(500.millis)

        result.value mustEqual Some(Success(false))
    }

    "Memoized effects can be canceled when there are no other active subscribers (2)" in ticked {
      implicit ticker =>
        val op = for {
          completed <- Ref[IO].of(false)
          action = IO.sleep(300.millis) >> completed.set(true)
          memoized <- Concurrent[IO].memoize(action)
          fiber1 <- memoized.start
          _ <- IO.sleep(100.millis)
          fiber2 <- memoized.start
          _ <- IO.sleep(100.millis)
          _ <- fiber2.cancel
          _ <- fiber1.cancel
          _ <- IO.sleep(400.millis)
          res <- completed.get
        } yield res

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll(600.millis)

        result.value mustEqual Some(Success(false))
    }

    "Memoized effects can be canceled when there are no other active subscribers (3)" in ticked {
      implicit ticker =>
        val op = for {
          completed <- Ref[IO].of(false)
          action = IO.sleep(300.millis) >> completed.set(true)
          memoized <- Concurrent[IO].memoize(action)
          fiber1 <- memoized.start
          _ <- IO.sleep(100.millis)
          fiber2 <- memoized.start
          _ <- IO.sleep(100.millis)
          _ <- fiber1.cancel
          _ <- fiber2.cancel
          _ <- IO.sleep(400.millis)
          res <- completed.get
        } yield res

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll(600.millis)

        result.value mustEqual Some(Success(false))
    }

    "Running a memoized effect after it was previously canceled reruns it" in ticked {
      implicit ticker =>
        val op = for {
          started <- Ref[IO].of(0)
          completed <- Ref[IO].of(0)
          action = started.update(_ + 1) >> IO.sleep(200.millis) >> completed.update(_ + 1)
          memoized <- Concurrent[IO].memoize(action)
          fiber <- memoized.start
          _ <- IO.sleep(100.millis)
          _ <- fiber.cancel
          _ <- memoized.timeout(1.second)
          v1 <- started.get
          v2 <- completed.get
        } yield v1 -> v2

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll(500.millis)

        result.value mustEqual Some(Success((2, 1)))
    }

    "Attempting to cancel a memoized effect with active subscribers is a no-op" in ticked {
      implicit ticker =>
        val op = for {
          condition <- Deferred[IO, Unit]
          action = IO.sleep(200.millis) >> condition.complete(())
          memoized <- Concurrent[IO].memoize(action)
          fiber1 <- memoized.start
          _ <- IO.sleep(50.millis)
          fiber2 <- memoized.start
          _ <- IO.sleep(50.millis)
          _ <- fiber1.cancel
          _ <- fiber2.join // Make sure no exceptions are swallowed by start
          v <- condition.get.timeout(1.second).as(true)
        } yield v

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll(500.millis)

        result.value mustEqual Some(Success(true))
    }

  }

}
