/*
 * Copyright 2020-2022 Typelevel
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

class RefSpec extends BaseRefSpec[Integer] {
  def mkRef(i: Integer) = IO.ref(i)
  val clazz = classOf[SyncRef[IO, Integer]]
  val a0 = 0
  val a1 = 1
  val a5 = 5
  val f = _ + 1
}

class BooleanRefSpec extends BaseRefSpec[Boolean] {
  def mkRef(b: Boolean) = IO.ref(b)
  val clazz = classOf[SyncBooleanRef[IO]]
  val a0 = false
  val a1 = true
  val a5 = true
  val f = !_
}

class IntRefSpec extends BaseRefSpec[Int] {
  def mkRef(i: Int) = IO.ref(i)
  val clazz = classOf[SyncIntRef[IO]]
  val a0 = 0
  val a1 = 1
  val a5 = 5
  val f = _ + 1
}

class LongRefSpec extends BaseRefSpec[Long] {
  def mkRef(l: Long) = IO.ref(l)
  val clazz = classOf[SyncLongRef[IO]]
  val a0 = 0L
  val a1 = 1L
  val a5 = 5L
  val f = _ + 1
}

abstract class BaseRefSpec[A] extends BaseSpec with DetectPlatform { outer =>

  def mkRef(a: A): IO[Ref[IO, A]]
  def clazz: Class[_]

  def a0: A
  def a1: A
  def a5: A
  def f: A => A

  val smallDelay: IO[Unit] = IO.sleep(20.millis)

  "ref" should {
    // TODO need parallel instance for IO
    // "support concurrent modifications" in ticked { implicit ticker =>
    //   val finalValue = 100
    //   val r = Ref.unsafe[IO, Int](0)
    //   val modifies = List.fill(finalValue)(r.update(_ + 1)).parSequence
    //   (modifies.start *> r.get) must completeAs(finalValue)

    // }

    "specialize" in ticked { implicit ticker =>
      mkRef(a0).map(clazz.isInstance(_)) must completeAs(true)
    }

    "get and set successfully" in ticked { implicit ticker =>
      val op = for {
        r <- mkRef(a0)
        getAndSetResult <- r.getAndSet(a1)
        getResult <- r.get
      } yield getAndSetResult == a0 && getResult == a1

      op must completeAs(true)

    }

    "get and update successfully" in ticked { implicit ticker =>
      val op = for {
        r <- mkRef(a0)
        getAndUpdateResult <- r.getAndUpdate(f)
        getResult <- r.get
      } yield getAndUpdateResult == a0 && getResult == a1

      op must completeAs(true)
    }

    "update and get successfully" in ticked { implicit ticker =>
      val op = for {
        r <- mkRef(a0)
        updateAndGetResult <- r.updateAndGet(f)
        getResult <- r.get
      } yield updateAndGetResult == a1 && getResult == a1

      op must completeAs(true)
    }

    "access successfully" in ticked { implicit ticker =>
      val op = for {
        r <- mkRef(a0)
        valueAndSetter <- r.access
        (value, setter) = valueAndSetter
        success <- setter(f(value))
        result <- r.get
      } yield success && result == a1

      op must completeAs(true)
    }

    "access - setter should fail if value is modified before setter is called" in ticked {
      implicit ticker =>
        val op = for {
          r <- mkRef(a0)
          valueAndSetter <- r.access
          (value, setter) = valueAndSetter
          _ <- r.set(a5)
          success <- setter(f(value))
          result <- r.get
        } yield !success && result == a5

        op must completeAs(true)
    }

    "tryUpdate - modification occurs successfully" in ticked { implicit ticker =>
      val op = for {
        r <- mkRef(a0)
        result <- r.tryUpdate(f)
        value <- r.get
      } yield result && value == a1

      op must completeAs(true)
    }

    if (!isJS && !isNative) // concurrent modification impossible
      "tryUpdate - should fail to update if modification has occurred" in ticked {
        implicit ticker =>
          val updateRefUnsafely: Ref[IO, A] => Unit = { (ref: Ref[IO, A]) =>
            unsafeRun(ref.update(f))
            ()
          }

          val op = for {
            r <- mkRef(a0)
            result <- r.tryUpdate { currentValue =>
              updateRefUnsafely(r)
              f(currentValue)
            }
          } yield result

          op must completeAs(false)
      }

    "tryModifyState - modification occurs successfully" in ticked { implicit ticker =>
      val op = for {
        r <- mkRef(a0)
        result <- r.tryModifyState(State.pure(a1))
      } yield result.contains(a1)

      op must completeAs(true)
    }

    "modifyState - modification occurs successfully" in ticked { implicit ticker =>
      val op = for {
        r <- mkRef(a0)
        result <- r.modifyState(State.pure(a1))
      } yield result == a1

      op must completeAs(true)
    }
  }

}
