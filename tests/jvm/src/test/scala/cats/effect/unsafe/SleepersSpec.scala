/*
 * Copyright 2020-2023 Typelevel
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

package cats.effect.unsafe

import org.specs2.mutable.Specification

import scala.annotation.tailrec
import scala.concurrent.duration._

class SleepersSpec extends Specification {

  "SleepCallback" should {
    "have a trigger time in the future" in {
      val sleepers = new TimerSkipList
      val now = 100.millis.toNanos
      val delay = 500.millis.toNanos
      sleepers.insertTlr(now, delay, _ => ())
      val triggerTime = sleepers.peekFirstTriggerTime()
      val expected = 600.millis.toNanos // delay + now

      triggerTime mustEqual expected
    }

    def dequeueAll(sleepers: TimerSkipList): List[(Long, Right[Nothing, Unit] => Unit)] = {
      @tailrec
      def loop(acc: List[(Long, Right[Nothing, Unit] => Unit)])
          : List[(Long, Right[Nothing, Unit] => Unit)] = {
        val tt = sleepers.peekFirstTriggerTime()
        if (tt == Long.MinValue) acc.reverse
        else {
          val cb = sleepers.pollFirstIfTriggered(now = tt)
          loop((tt, cb) :: acc)
        }
      }

      loop(Nil)
    }

    // creates a new callback, making sure it's a separate object:
    def newCb(): Right[Nothing, Unit] => Unit = {
      new Function1[Right[Nothing, Unit], Unit] { def apply(x: Right[Nothing, Unit]) = () }
    }

    "be ordered according to the trigger time" in {
      val sleepers = new TimerSkipList

      val now1 = 100.millis.toNanos
      val delay1 = 500.millis.toNanos
      val expected1 = 600.millis.toNanos // delay1 + now1

      val now2 = 200.millis.toNanos
      val delay2 = 100.millis.toNanos
      val expected2 = 300.millis.toNanos // delay2 + now2

      val now3 = 300.millis.toNanos
      val delay3 = 50.millis.toNanos
      val expected3 = 350.millis.toNanos // delay3 + now3

      val cb1 = newCb()
      val cb2 = newCb()
      val cb3 = newCb()

      sleepers.insertTlr(now1, delay1, cb1)
      sleepers.insertTlr(now2, delay2, cb2)
      sleepers.insertTlr(now3, delay3, cb3)

      val ordering = dequeueAll(sleepers)
      val expectedOrdering = List(expected2 -> cb2, expected3 -> cb3, expected1 -> cb1)

      ordering mustEqual expectedOrdering
    }

    "be ordered correctly even if Long overflows" in {
      val sleepers = new TimerSkipList

      val now1 = Long.MaxValue - 20L
      val delay1 = 10.nanos.toNanos
      val expected1 = Long.MaxValue - 10L // no overflow yet

      val now2 = Long.MaxValue - 5L
      val delay2 = 10.nanos.toNanos
      val expected2 = Long.MinValue + 4L // overflow

      val cb1 = newCb()
      val cb2 = newCb()

      sleepers.insertTlr(now1, delay1, cb1)
      sleepers.insertTlr(now2, delay2, cb2)

      val ordering = dequeueAll(sleepers)
      val expectedOrdering = List(expected1 -> cb1, expected2 -> cb2)

      ordering mustEqual expectedOrdering
    }
  }
}
