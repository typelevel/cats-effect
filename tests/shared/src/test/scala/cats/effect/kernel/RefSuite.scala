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

package cats
package effect
package kernel

import cats.data.State

import scala.concurrent.duration._

class RefSuite extends BaseSuite with DetectPlatform { outer =>

  val smallDelay: IO[Unit] = IO.sleep(20.millis)

  // TODO need parallel instance for IO
  // ticked("support concurrent modifications") { implicit ticker =>
  //   val finalValue = 100
  //   val r = Ref.unsafe[IO, Int](0)
  //   val modifies = List.fill(finalValue)(r.update(_ + 1)).parSequence
  //   (modifies.start *> assertCompleteAs(r.get), finalValue)

  // }

  ticked("get and set successfully") { implicit ticker =>
    val op = for {
      r <- Ref[IO].of(0)
      getAndSetResult <- r.getAndSet(1)
      getResult <- r.get
    } yield getAndSetResult == 0 && getResult == 1

    assertCompleteAs(op, true)

  }

  ticked("get and update successfully") { implicit ticker =>
    val op = for {
      r <- Ref[IO].of(0)
      getAndUpdateResult <- r.getAndUpdate(_ + 1)
      getResult <- r.get
    } yield getAndUpdateResult == 0 && getResult == 1

    assertCompleteAs(op, true)
  }

  ticked("update and get successfully") { implicit ticker =>
    val op = for {
      r <- Ref[IO].of(0)
      updateAndGetResult <- r.updateAndGet(_ + 1)
      getResult <- r.get
    } yield updateAndGetResult == 1 && getResult == 1

    assertCompleteAs(op, true)
  }

  ticked("access successfully") { implicit ticker =>
    val op = for {
      r <- Ref[IO].of(0)
      valueAndSetter <- r.access
      (value, setter) = valueAndSetter
      success <- setter(value + 1)
      result <- r.get
    } yield success && result == 1

    assertCompleteAs(op, true)
  }

  ticked("access - setter should fail if value is modified before setter is called") {
    implicit ticker =>
      val op = for {
        r <- Ref[IO].of(0)
        valueAndSetter <- r.access
        (value, setter) = valueAndSetter
        _ <- r.set(5)
        success <- setter(value + 1)
        result <- r.get
      } yield !success && result == 5

      assertCompleteAs(op, true)
  }

  ticked("tryUpdate - modification occurs successfully") { implicit ticker =>
    val op = for {
      r <- Ref[IO].of(0)
      result <- r.tryUpdate(_ + 1)
      value <- r.get
    } yield result && value == 1

    assertCompleteAs(op, true)
  }

  if (!isJS) // concurrent modification impossible
    ticked("tryUpdate - should fail to update if modification has occurred") {
      implicit ticker =>
        val updateRefUnsafely: Ref[IO, Int] => Unit = { (ref: Ref[IO, Int]) =>
          unsafeRun(ref.update(_ + 1))
          ()
        }

        val op = for {
          r <- Ref[IO].of(0)
          result <- r.tryUpdate { currentValue =>
            updateRefUnsafely(r)
            currentValue + 1
          }
        } yield result

        assertCompleteAs(op, false)
    }
  else ()

  ticked("tryModifyState - modification occurs successfully") { implicit ticker =>
    val op = for {
      r <- Ref[IO].of(0)
      result <- r.tryModifyState(State.pure(1))
    } yield result.contains(1)

    assertCompleteAs(op, true)
  }

  ticked("modifyState - modification occurs successfully") { implicit ticker =>
    val op = for {
      r <- Ref[IO].of(0)
      result <- r.modifyState(State.pure(1))
    } yield result == 1

    assertCompleteAs(op, true)
  }

  ticked("flatModify - finalizer should be uncancelable") { implicit ticker =>
    var passed = false
    val op = for {
      ref <- Ref[IO].of(0)
      _ <- ref
        .flatModify(_ => (1, IO.canceled >> IO { passed = true }))
        .start
        .flatMap(_.join)
        .void
      result <- ref.get
    } yield result == 1

    assertCompleteAs(op, true)
    assert(passed)
  }

  ticked("flatModifyFull - finalizer should mask cancellation") { implicit ticker =>
    var passed = false
    var failed = false
    val op = for {
      ref <- Ref[IO].of(0)
      _ <- ref
        .flatModifyFull { (poll, _) =>
          (1, poll(IO.canceled >> IO { failed = true }).onCancel(IO { passed = true }))
        }
        .start
        .flatMap(_.join)
        .void
      result <- ref.get
    } yield result == 1

    assertCompleteAs(op, true)
    assert(passed)
    assert(!failed)
  }

}
