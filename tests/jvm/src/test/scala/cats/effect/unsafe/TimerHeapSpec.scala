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

package cats.effect.unsafe

import org.specs2.mutable.Specification

class TimerHeapSpec extends Specification {

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

  "TimerHeap" should {

    "correctly insert / pollFirstIfTriggered" in {
      val m = new TimerHeap
      val out = new Array[Right[Nothing, Unit] => Unit](1)
      m.pollFirstIfTriggered(Long.MinValue) must beNull
      m.pollFirstIfTriggered(Long.MaxValue) must beNull
      m.toString mustEqual "TimerHeap()"

      m.insert(0L, 0L, cb0, out)
      out(0) must beNull
      m.toString mustEqual "TimerHeap(...)"
      m.pollFirstIfTriggered(Long.MinValue + 1) must beNull
      m.pollFirstIfTriggered(Long.MaxValue) mustEqual cb0
      m.pollFirstIfTriggered(Long.MaxValue) must beNull
      m.pollFirstIfTriggered(Long.MinValue) must beNull

      m.insert(0L, 10L, cb0, out)
      out(0) must beNull
      m.insert(0L, 30L, cb1, out)
      out(0) must beNull
      m.insert(0L, 0L, cb2, out)
      out(0) must beNull
      m.insert(0L, 20L, cb3, out)
      out(0) mustEqual cb2
      m.pollFirstIfTriggered(-1L) must beNull
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
      val m = new TimerHeap
      val out = new Array[Right[Nothing, Unit] => Unit](1)
      val r0 = m.insert(0L, 1L, cb0, out)
      out(0) must beNull
      val r1 = m.insert(0L, 2L, cb1, out)
      out(0) must beNull
      val r5 = m.insert(0L, 6L, cb5, out)
      out(0) must beNull
      val r4 = m.insert(0L, 5L, cb4, out)
      out(0) must beNull
      val r2 = m.insert(0L, 3L, cb2, out)
      out(0) must beNull
      val r3 = m.insert(0L, 4L, cb3, out)
      out(0) must beNull

      m.peekFirstQuiescent() mustEqual cb0
      m.peekFirstTriggerTime() mustEqual 1L
      r0.run()
      m.peekFirstTriggerTime() mustEqual 2L
      m.peekFirstQuiescent() mustEqual cb1
      m.pollFirstIfTriggered(Long.MaxValue) mustEqual cb1
      m.peekFirstQuiescent() mustEqual cb2
      m.peekFirstTriggerTime() mustEqual 3L
      r1.run() // NOP
      r3.run()
      m.packIfNeeded()
      m.peekFirstQuiescent() mustEqual cb2
      m.peekFirstTriggerTime() mustEqual 3L
      m.pollFirstIfTriggered(Long.MaxValue) mustEqual cb2
      m.peekFirstQuiescent() mustEqual cb4
      m.peekFirstTriggerTime() mustEqual 5L
      m.pollFirstIfTriggered(Long.MaxValue) mustEqual cb4
      m.peekFirstQuiescent() mustEqual cb5
      m.peekFirstTriggerTime() mustEqual 6L
      r2.run()
      r5.run()
      m.packIfNeeded()
      m.peekFirstQuiescent() must beNull
      m.peekFirstTriggerTime() mustEqual Long.MinValue
      m.pollFirstIfTriggered(Long.MaxValue) must beNull
      r4.run() // NOP
      m.packIfNeeded()
      m.pollFirstIfTriggered(Long.MaxValue) must beNull
    }

    "behave correctly when nanoTime wraps around" in {
      val m = new TimerHeap
      val startFrom = Long.MaxValue - 100L
      var nanoTime = startFrom
      val removers = new Array[Runnable](200)
      val callbacksBuilder = Vector.newBuilder[Right[Nothing, Unit] => Unit]
      val triggeredBuilder = Vector.newBuilder[Right[Nothing, Unit] => Unit]
      for (i <- 0 until 200) {
        if (i >= 10 && i % 2 == 0) removers(i - 10).run()
        val cb = newCb()
        val out = new Array[Right[Nothing, Unit] => Unit](1)
        val r = m.insert(nanoTime, 10L, cb, out)
        triggeredBuilder ++= Option(out(0))
        removers(i) = r
        callbacksBuilder += cb
        nanoTime += 1L
      }
      for (idx <- 190 until removers.size by 2) {
        removers(idx).run()
      }
      nanoTime += 100L
      val callbacks = callbacksBuilder.result()
      while ({
        val cb = m.pollFirstIfTriggered(nanoTime)
        triggeredBuilder ++= Option(cb)
        cb ne null
      }) {}
      val triggered = triggeredBuilder.result()

      val nonCanceled = callbacks.grouped(2).map(_.last).toVector
      triggered should beEqualTo(nonCanceled)

      ok
    }
  }
}
