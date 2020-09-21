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
import cats.syntax.all._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class RefTests extends CatsEffectSuite {
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  private val smallDelay: IO[Unit] = timer.sleep(20.millis)

  private def awaitEqual[A: Eq](t: IO[A], success: A): IO[Unit] =
    t.flatMap(a => if (Eq[A].eqv(a, success)) IO.unit else smallDelay *> awaitEqual(t, success))

  test("concurrent modifications") {
    val finalValue = 100
    val r = Ref.unsafe[IO, Int](0)
    val modifies = List.fill(finalValue)(IO.shift *> r.update(_ + 1)).parSequence
    (IO.shift *> modifies.start *> awaitEqual(r.get, finalValue)).as(assert(true))
  }

  test("getAndSet - successful") {
    for {
      r <- Ref[IO].of(0)
      getAndSetResult <- r.getAndSet(1)
      getResult <- r.get
    } yield assertEquals((getAndSetResult, getResult), (0, 1))
  }

  test("getAndUpdate - successful") {
    for {
      r <- Ref[IO].of(0)
      getAndUpdateResult <- r.getAndUpdate(_ + 1)
      getResult <- r.get
    } yield ((getAndUpdateResult, getResult), (0, 1))
  }

  test("updateAndGet - successful") {
    for {
      r <- Ref[IO].of(0)
      updateAndGetResult <- r.updateAndGet(_ + 1)
      getResult <- r.get
    } yield assertEquals((updateAndGetResult, getResult), (1, 1))
  }

  test("access - successful") {
    for {
      r <- Ref[IO].of(0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      success <- setter(value + 1)
      result <- r.get
    } yield assertEquals((success, result), (true, 1))
  }

  test("access - setter should fail if value is modified before setter is called") {
    for {
      r <- Ref[IO].of(0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      _ <- r.set(5)
      success <- setter(value + 1)
      result <- r.get
    } yield assertEquals((success, result), (false, 5))
  }

  test("access - setter should fail if called twice") {
    for {
      r <- Ref[IO].of(0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      cond1 <- setter(value + 1)
      _ <- r.set(value)
      cond2 <- setter(value + 1)
      result <- r.get
    } yield assertEquals((cond1, cond2, result), (true, false, 0))
  }

  test("tryUpdate - modification occurs successfully") {
    for {
      r <- Ref[IO].of(0)
      result <- r.tryUpdate(_ + 1)
      value <- r.get
    } yield assertEquals((result, value), (true, 1))
  }

  test("tryUpdate - should fail to update if modification has occurred") {
    val updateRefUnsafely: Ref[IO, Int] => Unit = _.update(_ + 1).unsafeRunSync()

    for {
      r <- Ref[IO].of(0)
      v <- r.tryUpdate { currentValue =>
        updateRefUnsafely(r)
        currentValue + 1
      }
    } yield assertEquals(v, false)
  }

  test("updateMaybe - successful") {
    for {
      r <- Ref[IO].of(0)
      v <- r.updateMaybe(_ => Some(1))
    } yield assertEquals(v, true)
  }

  test("updateMaybe - short-circuit") {
    for {
      r <- Ref[IO].of(0)
      v <- r.updateMaybe(_ => None)
    } yield assertEquals(v, false)
  }
  test("modifyMaybe - successful") {
    for {
      r <- Ref[IO].of(0)
      v <- r.modifyMaybe(_ => Some((1, 2)))
    } yield assertEquals(v, Some(2))
  }

  test("modifyMaybe - short-circuit") {
    for {
      r <- Ref[IO].of(0)
      v <- r.modifyMaybe(_ => None)
    } yield assertEquals(v, None)
  }

  test("updateOr - successful") {
    for {
      r <- Ref[IO].of(0)
      v <- r.updateOr(_ => Right(1))
    } yield assertEquals(v, None)
  }

  test("updateOr - short-circuit") {
    for {
      r <- Ref[IO].of(0)
      v <- r.updateOr(_ => Left("fail"))
    } yield assertEquals(v, Some("fail"))
  }

  test("modifyOr - successful") {
    for {
      r <- Ref[IO].of(0)
      v <- r.modifyOr(_ => Right((1, "success")))
    } yield assertEquals(v, Right("success"))
  }

  test("modifyOr - short-circuit") {
    for {
      r <- Ref[IO].of(0)
      v <- r.modifyOr(_ => Left("fail"))
    } yield assertEquals(v, Left("fail"))
  }

  test("tryModifyState - modification occurs successfully") {
    for {
      r <- Ref[IO].of(0)
      v <- r.tryModifyState(State.pure(1))
    } yield assert(v.contains(1))
  }

  test("modifyState - modification occurs successfully") {
    for {
      r <- Ref[IO].of(0)
      v <- r.modifyState(State.pure(1))
    } yield assertEquals(v, 1)
  }
}
