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

package cats
package effect
package kernel

import cats.data.State

import scala.concurrent.duration._

class RefSpec extends BaseSpec with DetectPlatform { outer =>

  val smallDelay: IO[Unit] = IO.sleep(20.millis)

  "ref" should {
    // TODO need parallel instance for IO
    // "support concurrent modifications" in ticked { implicit ticker =>
    //   val finalValue = 100
    //   val r = Ref.unsafe[IO, Int](0)
    //   val modifies = List.fill(finalValue)(r.update(_ + 1)).parSequence
    //   (modifies.start *> r.get) must completeAs(finalValue)

    // }

    "get and set successfully" in ticked { implicit ticker =>
      val op = for {
        r <- Ref[IO].of(0)
        getAndSetResult <- r.getAndSet(1)
        getResult <- r.get
      } yield getAndSetResult == 0 && getResult == 1

      op must completeAs(true)

    }

    "get and update successfully" in ticked { implicit ticker =>
      val op = for {
        r <- Ref[IO].of(0)
        getAndUpdateResult <- r.getAndUpdate(_ + 1)
        getResult <- r.get
      } yield getAndUpdateResult == 0 && getResult == 1

      op must completeAs(true)
    }

    "update and get successfully" in ticked { implicit ticker =>
      val op = for {
        r <- Ref[IO].of(0)
        updateAndGetResult <- r.updateAndGet(_ + 1)
        getResult <- r.get
      } yield updateAndGetResult == 1 && getResult == 1

      op must completeAs(true)
    }

    "access successfully" in ticked { implicit ticker =>
      val op = for {
        r <- Ref[IO].of(0)
        valueAndSetter <- r.access
        (value, setter) = valueAndSetter
        success <- setter(value + 1)
        result <- r.get
      } yield success && result == 1

      op must completeAs(true)
    }

    "access - setter should fail if value is modified before setter is called" in ticked {
      implicit ticker =>
        val op = for {
          r <- Ref[IO].of(0)
          valueAndSetter <- r.access
          (value, setter) = valueAndSetter
          _ <- r.set(5)
          success <- setter(value + 1)
          result <- r.get
        } yield !success && result == 5

        op must completeAs(true)
    }

    "tryUpdate - modification occurs successfully" in ticked { implicit ticker =>
      val op = for {
        r <- Ref[IO].of(0)
        result <- r.tryUpdate(_ + 1)
        value <- r.get
      } yield result && value == 1

      op must completeAs(true)
    }

    if (!isJS && !isNative) // concurrent modification impossible
      "tryUpdate - should fail to update if modification has occurred" in ticked {
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

          op must completeAs(false)
      }
    else ()

    "tryModifyState - modification occurs successfully" in ticked { implicit ticker =>
      val op = for {
        r <- Ref[IO].of(0)
        result <- r.tryModifyState(State.pure(1))
      } yield result.contains(1)

      op must completeAs(true)
    }

    "modifyState - modification occurs successfully" in ticked { implicit ticker =>
      val op = for {
        r <- Ref[IO].of(0)
        result <- r.modifyState(State.pure(1))
      } yield result == 1

      op must completeAs(true)
    }

    "flatModify - finalizer should be uncancelable" in ticked { implicit ticker =>
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

      op must completeAs(true)
      passed must beTrue
    }

    "flatModifyFull - finalizer should mask cancellation" in ticked { implicit ticker =>
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

      op must completeAs(true)
      passed must beTrue
      failed must beFalse
    }

  }

}
