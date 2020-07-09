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
package concurrent

import cats.{Eq, Order, Show}
import cats.data.State
import cats.kernel.laws.discipline.MonoidTests
import cats.effect.laws.EffectTests
import cats.effect.testkit.{AsyncGenerators, BracketGenerators, GenK, OutcomeGenerators, TestContext}
import cats.implicits._

import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}
// import org.scalacheck.rng.Seed

import org.specs2.ScalaCheck
// import org.specs2.scalacheck.Parameters
import org.specs2.matcher.Matcher

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.TimeUnit

class RefSpec extends IOPlatformSpecification with Discipline with ScalaCheck with BaseSpec { outer =>

  import OutcomeGenerators._

  sequential

  val ctx = TestContext()

  val smallDelay: IO[Unit] = IO.sleep(20 millis)

  "ref" should {
    //TODO need parallel instance for IO
    // "support concurrent modifications" in {
    //   val finalValue = 100
    //   val r = Ref.unsafe[IO, Int](0)
    //   val modifies = List.fill(finalValue)(r.update(_ + 1)).parSequence
    //   (modifies.start *> r.get) must completeAs(finalValue)

    // }

    "get and set successfully" in {
      val op = for {
        r <- Ref[IO].of(0)
        getAndSetResult <- r.getAndSet(1)
        getResult <- r.get
      } yield getAndSetResult == 0 && getResult == 1

      op must completeAs(true)

    }

    "get and update successfully" in {
      val op = for {
        r <- Ref[IO].of(0)
        getAndUpdateResult <- r.getAndUpdate(_ + 1)
        getResult <- r.get
      } yield getAndUpdateResult == 0 && getResult == 1

      op must completeAs(true)
    }

    "update and get successfully" in {
      val op = for {
        r <- Ref[IO].of(0)
        updateAndGetResult <- r.updateAndGet(_ + 1)
        getResult <- r.get
      } yield updateAndGetResult == 1 && getResult == 1

      op must completeAs(true)
    }

    "access successfully" in {
      val op = for {
        r <- Ref[IO].of(0)
        valueAndSetter <- r.access
        (value, setter) = valueAndSetter
        success <- setter(value + 1)
        result <- r.get
      } yield success && result == 1

      op must completeAs(true)
    }

    "access - setter should fail if value is modified before setter is called" in {
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

    "access - setter should fail if called twice" in {
      val op = for {
        r <- Ref[IO].of(0)
        valueAndSetter <- r.access
        (value, setter) = valueAndSetter
        cond1 <- setter(value + 1)
        _ <- r.set(value)
        cond2 <- setter(value + 1)
        result <- r.get
      } yield cond1 && !cond2 && result == 0

      op must completeAs(true)
    }

    "access - setter should fail if called twice" in {
      val op = for {
        r <- Ref[IO].of(0)
        valueAndSetter <- r.access
        (value, setter) = valueAndSetter
        cond1 <- setter(value + 1)
        _ <- r.set(value)
        cond2 <- setter(value + 1)
        result <- r.get
      } yield cond1 && !cond2 && result == 0

      op must completeAs(true)
    }

    "tryUpdate - modification occurs successfully" in {
      val op = for {
        r <- Ref[IO].of(0)
        result <- r.tryUpdate(_ + 1)
        value <- r.get
      } yield result && value == 1

      op must completeAs(true)
    }

    "tryUpdate - should fail to update if modification has occurred" in {
      val updateRefUnsafely: Ref[IO, Int] => Unit = (ref: Ref[IO, Int]) => unsafeRun(ref.update(_ + 1))

      val op = for {
        r <- Ref[IO].of(0)
        result <- r.tryUpdate { currentValue =>
          updateRefUnsafely(r)
          currentValue + 1
        }
      } yield result

      op must completeAs(false)
    }

    "tryModifyState - modification occurs successfully" in {
      val op = for {
        r <- Ref[IO].of(0)
        result <- r.tryModifyState(State.pure(1))
      } yield result.contains(1)

      op must completeAs(true)
    }

    "modifyState - modification occurs successfully" in {
      val op = for {
        r <- Ref[IO].of(0)
        result <- r.modifyState(State.pure(1))
      } yield result == 1

      op must completeAs(true)
    }
  }




}
