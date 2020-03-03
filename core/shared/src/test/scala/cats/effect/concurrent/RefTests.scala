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
package concurrent

import cats.data.State
import cats.implicits._
import org.scalatest.Succeeded
import org.scalatest.matchers.should.Matchers
import org.scalatest.compatible.Assertion
import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class RefTests extends AsyncFunSuite with Matchers {
  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  private val smallDelay: IO[Unit] = timer.sleep(20.millis)

  private def awaitEqual[A: Eq](t: IO[A], success: A): IO[Unit] =
    t.flatMap(a => if (Eq[A].eqv(a, success)) IO.unit else smallDelay *> awaitEqual(t, success))

  private def run(t: IO[Unit]): Future[Assertion] = t.as(Succeeded).unsafeToFuture()

  test("concurrent modifications") {
    val finalValue = 100
    val r = Ref.unsafe[IO, Int](0)
    val modifies = List.fill(finalValue)(IO.shift *> r.update(_ + 1)).parSequence
    run(IO.shift *> modifies.start *> awaitEqual(r.get, finalValue))
  }

  test("getAndSet - successful") {
    val op = for {
      r <- Ref[IO].of(0)
      getAndSetResult <- r.getAndSet(1)
      getResult <- r.get
    } yield getAndSetResult == 0 && getResult == 1

    run(op.map(_ shouldBe true).void)
  }

  test("getAndUpdate - successful") {
    val op = for {
      r <- Ref[IO].of(0)
      getAndUpdateResult <- r.getAndUpdate(_ + 1)
      getResult <- r.get
    } yield getAndUpdateResult == 0 && getResult == 1

    run(op.map(_ shouldBe true).void)
  }

  test("updateAndGet - successful") {
    val op = for {
      r <- Ref[IO].of(0)
      updateAndGetResult <- r.updateAndGet(_ + 1)
      getResult <- r.get
    } yield updateAndGetResult == 1 && getResult == 1

    run(op.map(_ shouldBe true).void)
  }

  test("access - successful") {
    val op = for {
      r <- Ref[IO].of(0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      success <- setter(value + 1)
      result <- r.get
    } yield success && result == 1
    run(op.map(_ shouldBe true).void)
  }

  test("access - setter should fail if value is modified before setter is called") {
    val op = for {
      r <- Ref[IO].of(0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      _ <- r.set(5)
      success <- setter(value + 1)
      result <- r.get
    } yield !success && result == 5
    run(op.map(_ shouldBe true).void)
  }

  test("access - setter should fail if called twice") {
    val op = for {
      r <- Ref[IO].of(0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      cond1 <- setter(value + 1)
      _ <- r.set(value)
      cond2 <- setter(value + 1)
      result <- r.get
    } yield cond1 && !cond2 && result == 0
    run(op.map(_ shouldBe true).void)
  }

  test("tryUpdate - modification occurs successfully") {
    val op = for {
      r <- Ref[IO].of(0)
      result <- r.tryUpdate(_ + 1)
      value <- r.get
    } yield result && value == 1

    run(op.map(_ shouldBe true).void)
  }

  test("tryUpdate - should fail to update if modification has occurred") {
    val updateRefUnsafely: Ref[IO, Int] => Unit = _.update(_ + 1).unsafeRunSync()

    val op = for {
      r <- Ref[IO].of(0)
      result <- r.tryUpdate { currentValue =>
        updateRefUnsafely(r)
        currentValue + 1
      }
    } yield result

    run(op.map(_ shouldBe false).void)
  }

  test("tryModifyState - modification occurs successfully") {
    val op = for {
      r <- Ref[IO].of(0)
      result <- r.tryModifyState(State.pure(1))
    } yield result.contains(1)

    run(op.map(_ shouldBe true).void)
  }

  test("modifyState - modification occurs successfully") {
    val op = for {
      r <- Ref[IO].of(0)
      result <- r.modifyState(State.pure(1))
    } yield result == 1

    run(op.map(_ shouldBe true).void)
  }
}
