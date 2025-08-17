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
package std

import scala.concurrent.duration._

final class AtomicCellSuite extends BaseSuite {

  tests("AsyncAtomicCell", AtomicCell.async)
  tests("ConcurrentAtomicCell", AtomicCell.concurrent)

  def tests(name: String, factory: Int => IO[AtomicCell[IO, Int]]) = {

    real(s"$name get and set successfully") {
      val op = for {
        cell <- factory(0)
        getAndSetResult <- cell.getAndSet(1)
        getResult <- cell.get
      } yield getAndSetResult == 0 && getResult == 1

      op.mustEqual(true)
    }

    real(s"$name get and update successfully") {
      val op = for {
        cell <- factory(0)
        getAndUpdateResult <- cell.getAndUpdate(_ + 1)
        getResult <- cell.get
      } yield getAndUpdateResult == 0 && getResult == 1

      op.mustEqual(true)
    }

    real(s"$name update and get successfully") {
      val op = for {
        cell <- factory(0)
        updateAndGetResult <- cell.updateAndGet(_ + 1)
        getResult <- cell.get
      } yield updateAndGetResult == 1 && getResult == 1

      op.mustEqual(true)
    }

    ticked(s"$name evalModify successfully") { implicit ticker =>
      val op = factory(0).flatMap { cell =>
        cell.evalModify { x =>
          val y = x + 1
          IO.sleep(1.second).as((y, (x, y)))
        } map {
          case (oldValue, newValue) =>
            oldValue == 0 && newValue == 1
        }
      }

      assertCompleteAs(op, true)
    }

    ticked(s"$name evalUpdate should block and cancel should release") { implicit ticker =>
      val op = for {
        cell <- factory(0)
        b <- cell.evalUpdate(x => IO.never.as(x + 1)).start
        _ <- IO.sleep(1.second)
        f <- cell.update(_ + 3).start
        _ <- IO.sleep(1.second)
        _ <- f.cancel
        _ <- IO.sleep(1.second)
        _ <- b.cancel
        _ <- IO.sleep(1.second)
        _ <- cell.update(_ + 1)
        r <- cell.get
      } yield r == 1

      assertCompleteAs(op, true)
    }

    ticked(s"$name evalModify should properly suspend read") { implicit ticker =>
      val op = for {
        cell <- factory(0)
        _ <- cell.update(_ + 1).replicateA_(2)
        r <- cell.get
      } yield r == 2

      assertCompleteAs(op, true)
    }

    ticked(s"$name get should not block during concurrent modification") { implicit ticker =>
      val op = for {
        cell <- factory(0)
        gate <- IO.deferred[Unit]
        _ <- cell.evalModify(_ => gate.complete(()) *> IO.never).start
        _ <- gate.get
        r <- cell.get
      } yield r == 0

      assertCompleteAs(op, true)
    }

  }
}
