/*
 * Copyright 2020-2021 Typelevel
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

import scala.collection.mutable.PriorityQueue
import scala.concurrent.duration._

class SleepCallbackSpec extends Specification {

  "SleepCallback" should {
    "have a trigger time in the future" in {
      val sleepers = PriorityQueue.empty[SleepCallback]
      val now = 100.millis.toNanos
      val delay = 500.millis
      val scb = SleepCallback.create(delay, () => (), now, sleepers)
      val expected = 600.millis.toNanos // delay.toNanos + now

      scb.triggerTime mustEqual expected
    }

    "be ordered according to the trigger time" in {
      val sleepers = PriorityQueue.empty[SleepCallback]

      val now1 = 100.millis.toNanos
      val delay1 = 500.millis
      val expected1 = 600.millis.toNanos // delay1.toNanos + now1

      val now2 = 200.millis.toNanos
      val delay2 = 100.millis
      val expected2 = 300.millis.toNanos // delay2.toNanos + now2

      val now3 = 300.millis.toNanos
      val delay3 = 50.millis
      val expected3 = 350.millis.toNanos // delay3.toNanos + now3

      val scb1 = SleepCallback.create(delay1, () => (), now1, sleepers)
      val scb2 = SleepCallback.create(delay2, () => (), now2, sleepers)
      val scb3 = SleepCallback.create(delay3, () => (), now3, sleepers)

      scb1.triggerTime mustEqual expected1
      scb2.triggerTime mustEqual expected2
      scb3.triggerTime mustEqual expected3

      expected1 must be greaterThan expected2
      scb1 must be lessThan scb2 // uses the reverse `Ordering` instance

      expected1 must be greaterThan expected3
      scb1 must be lessThan scb3 // uses the reverse `Ordering` instance

      expected3 must be greaterThan expected2
      scb3 must be lessThan scb2 // uses the reverse `Ordering` instance

      sleepers += scb1
      sleepers += scb2
      sleepers += scb3

      val ordering = sleepers.dequeueAll
      val expectedOrdering = List(scb2, scb3, scb1)

      ordering mustEqual expectedOrdering
    }

    "summon the implicit ordering evidence" in {
      val _ = implicitly[Ordering[SleepCallback]]
      ok
    }
  }
}
