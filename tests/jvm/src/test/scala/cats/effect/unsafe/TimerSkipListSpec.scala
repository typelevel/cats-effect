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

class TimerSkipListSpec extends Specification {

  /**
   * Creates a new callback, making sure it's a separate object
   */
  def newCb(): Right[Nothing, Unit] => Unit = {
    new Function1[Right[Nothing, Unit], Unit] { def apply(x: Right[Nothing, Unit]) = () }
  }

  private val cb0 = newCb()
  private val cb1 = newCb()
  private val cb2 = newCb()
  private val cb3 = newCb()
  private val cb4 = newCb()
  private val cb5 = newCb()

  "TimerSkipList" should {

    "correctly insert / pollFirstIfTriggered" in {
      val m = new TimerSkipList
      m.pollFirstIfTriggered(Long.MinValue) must beNull
      m.pollFirstIfTriggered(Long.MaxValue) must beNull
      m.toString mustEqual "TimerSkipList()"

      m.insertTlr(0L, 0L, cb0)
      m.toString mustEqual "TimerSkipList(...)"
      m.pollFirstIfTriggered(Long.MinValue) must beNull
      m.pollFirstIfTriggered(Long.MaxValue) mustEqual cb0
      m.pollFirstIfTriggered(Long.MaxValue) must beNull
      m.pollFirstIfTriggered(Long.MinValue) must beNull

      m.insertTlr(0L, 10L, cb0)
      m.insertTlr(0L, 30L, cb1)
      m.insertTlr(0L, 0L, cb2)
      m.insertTlr(0L, 20L, cb3)
      m.pollFirstIfTriggered(-1L) must beNull
      m.pollFirstIfTriggered(0L) mustEqual cb2
      m.pollFirstIfTriggered(0L) must beNull
      m.pollFirstIfTriggered(10L) mustEqual cb0
      m.pollFirstIfTriggered(10L) must beNull
      m.pollFirstIfTriggered(20L) mustEqual cb3
      m.pollFirstIfTriggered(20L) must beNull
      m.pollFirstIfTriggered(30L) mustEqual cb1
      m.pollFirstIfTriggered(30L) must beNull
      m.pollFirstIfTriggered(Long.MaxValue) must beNull
    }

    "correctly insert / remove (cancel)" in {
      val m = new TimerSkipList
      val r0 = m.insertTlr(0L, 0L, cb0)
      val r1 = m.insertTlr(0L, 1L, cb1)
      val r5 = m.insertTlr(0L, 5L, cb5)
      val r4 = m.insertTlr(0L, 4L, cb4)
      val r2 = m.insertTlr(0L, 2L, cb2)
      val r3 = m.insertTlr(0L, 3L, cb3)

      m.peekFirstQuiescent() mustEqual cb0
      m.peekFirstTriggerTime() mustEqual 0L
      r0.run()
      m.peekFirstQuiescent() mustEqual cb1
      m.peekFirstTriggerTime() mustEqual 1L
      m.pollFirstIfTriggered(Long.MaxValue) mustEqual cb1
      m.peekFirstQuiescent() mustEqual cb2
      m.peekFirstTriggerTime() mustEqual 2L
      r1.run() // NOP
      r3.run()
      m.peekFirstQuiescent() mustEqual cb2
      m.peekFirstTriggerTime() mustEqual 2L
      m.pollFirstIfTriggered(Long.MaxValue) mustEqual cb2
      m.peekFirstQuiescent() mustEqual cb4
      m.peekFirstTriggerTime() mustEqual 4L
      m.pollFirstIfTriggered(Long.MaxValue) mustEqual cb4
      m.peekFirstQuiescent() mustEqual cb5
      m.peekFirstTriggerTime() mustEqual 5L
      r2.run()
      r5.run()
      m.peekFirstQuiescent() must beNull
      m.peekFirstTriggerTime() mustEqual Long.MinValue
      m.pollFirstIfTriggered(Long.MaxValue) must beNull
      r4.run() // NOP
      m.pollFirstIfTriggered(Long.MaxValue) must beNull
    }

    "behave correctly when nanoTime wraps around" in {
      val m = new TimerSkipList
      val startFrom = Long.MaxValue - 100L
      var nanoTime = startFrom
      val removersBuilder = Vector.newBuilder[Runnable]
      val callbacksBuilder = Vector.newBuilder[Right[Nothing, Unit] => Unit]
      for (_ <- 0 until 200) {
        val cb = newCb()
        val r = m.insertTlr(nanoTime, 10L, cb)
        removersBuilder += r
        callbacksBuilder += cb
        nanoTime += 1L
      }
      val removers = removersBuilder.result()
      for (idx <- 0 until removers.size by 2) {
        removers(idx).run()
      }
      nanoTime += 100L
      val callbacks = callbacksBuilder.result()
      for (i <- 0 until 200 by 2) {
        val cb = m.pollFirstIfTriggered(nanoTime)
        val expected = callbacks(i + 1)
        cb mustEqual expected
      }

      ok
    }
  }
}
