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

import cats.syntax.all._

import scala.concurrent.duration._

class AtomicMapSuite extends BaseSuite {
  tests("ConcurrentAtomicMap", AtomicMap.apply[IO, Int, Int])

  def tests(name: String, atomicMap: IO[AtomicMap[IO, Int, Option[Int]]]) = {
    real(
      s"${name} should getAndSet successfully if the given key is free"
    ) {
      val p = for {
        am <- atomicMap
        cell = am(key = 1)
        getAndSetResult <- cell.getAndSet(Some(1))
        getResult <- cell.get
      } yield getAndSetResult == None &&
        getResult == Some(1)

      p.mustEqual(true)
    }

    ticked(
      s"${name} get should not block during concurrent modification of the same key"
    ) { implicit ticker =>
      val p = for {
        am <- atomicMap
        cell = am(key = 1)
        gate <- IO.deferred[Unit]
        _ <- cell.evalModify(_ => gate.complete(()) *> IO.never).start
        _ <- gate.get
        getResult <- cell.get
      } yield getResult == None

      assertCompleteAs(p, true)
    }

    ticked(
      s"${name} should block action if not free in the given key"
    ) { implicit ticker =>
      val p = atomicMap.flatMap { am =>
        val cell = am(key = 1)

        cell.evalUpdate(_ => IO.never) >>
          cell.evalUpdate(IO.pure)
      }

      assertNonTerminate(p)
    }

    ticked(
      s"${name} should not block action if using a different key"
    ) { implicit ticker =>
      val p = atomicMap.flatMap { am =>
        val cell1 = am(key = 1)
        val cell2 = am(key = 2)

        IO.race(
          cell1.evalUpdate(_ => IO.never),
          cell2.evalUpdate(IO.pure)
        ).void
      }

      assertCompleteAs(p, ())
    }

    ticked(
      s"${name} should support concurrent usage in the same key"
    ) { implicit ticker =>
      val p = atomicMap.flatMap { am =>
        val cell = am(key = 1)
        val usage = IO.sleep(1.second) >> cell.evalUpdate(IO.pure(_).delayBy(1.second))

        (usage, usage).parTupled.void
      }

      assertCompleteAs(p, ())
    }

    ticked(
      s"${name} should support concurrent usage in different keys"
    ) { implicit ticker =>
      val p = atomicMap.flatMap { am =>
        def usage(key: Int): IO[Unit] = {
          val cell = am(key)
          IO.sleep(1.second) >> cell.evalUpdate(IO.pure(_).delayBy(1.second))
        }

        List.range(start = 1, end = 10).parTraverse_(usage)
      }

      assertCompleteAs(p, ())
    }

    real(
      s"${name} should support OptionOps"
    ) {
      val p = for {
        am <- atomicMap
        key = 1
        default = 0
        value = 1
        updateFunction = (x: Int) => x + 1
        cell = am(key)
        cellGetResultBeforeSetValue <- cell.get
        amGetOrElseResultBeforeSetValue <- am.getOrElse(key, default)
        _ <- am.setValue(key, value)
        cellGetResultAfterSetValue <- cell.get
        amGetOrElseResultAfterSetValue <- am.getOrElse(key, default)
        _ <- am.updateValueIfSet(key)(updateFunction)
        cellGetResultAfterUpdate <- cell.get
        amGetOrElseResultAfterUpdate <- am.getOrElse(key, default)
        _ <- am.unsetKey(key)
        cellGetResultAfterUnsetKey <- cell.get
        amGetOrElseResultAfterUnsetKey <- am.getOrElse(key, default)
      } yield cellGetResultBeforeSetValue == None &&
        amGetOrElseResultBeforeSetValue == default &&
        cellGetResultAfterSetValue == Some(value) &&
        amGetOrElseResultAfterSetValue == value &&
        cellGetResultAfterUpdate == Some(updateFunction(value)) &&
        amGetOrElseResultAfterUpdate == updateFunction(value) &&
        cellGetResultAfterUnsetKey == None &&
        amGetOrElseResultAfterUnsetKey == default

      p.mustEqual(true)
    }

    real(
      s"A defaulted ${name} cell should be consistent with its underlying AtomicCell"
    ) {
      val p = for {
        am <- atomicMap
        default = 0
        key = 1
        value = 1
        cell = am(key)
        defaultedAM = AtomicMap.defaultedAtomicMap(am, default)
        defaultedCell = defaultedAM(key)
        cellGetResultBeforeModification <- cell.get
        defaultedCellGetResultBeforeModification <- defaultedCell.get
        _ <- defaultedCell.set(value)
        cellGetResultAfterModification <- cell.get
        defaultedCellGetResultAfterModification <- defaultedCell.get
        _ <- defaultedCell.set(default)
        cellGetResultAfterClear <- cell.get
        defaultedCellGetResultAfterClear <- defaultedCell.get
      } yield cellGetResultBeforeModification == None &&
        defaultedCellGetResultBeforeModification == default &&
        cellGetResultAfterModification == Some(value) &&
        defaultedCellGetResultAfterModification == value &&
        cellGetResultAfterClear == None &&
        defaultedCellGetResultAfterClear == default

      p.mustEqual(true)
    }
  }
}
