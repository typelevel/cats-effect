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

package cats.effect.unsafe

import cats.effect.BaseSuite

class TimerHeapSuite extends BaseSuite {

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

  testUnit("correctly insert / pollFirstIfTriggered") {
    val m = new TimerHeap
    val out = new Array[Right[Nothing, Unit] => Unit](1)
    assert(m.pollFirstIfTriggered(Long.MinValue) eq null)
    assert(m.pollFirstIfTriggered(Long.MaxValue) eq null)
    assertEquals(m.toString, "TimerHeap()")

    m.insert(0L, 0L, cb0, out)
    assert(out(0) eq null)
    assertEquals(m.toString, "TimerHeap(...)")
    assert(m.pollFirstIfTriggered(Long.MinValue + 1) eq null)
    assertEquals(m.pollFirstIfTriggered(Long.MaxValue), cb0)
    assert(m.pollFirstIfTriggered(Long.MaxValue) eq null)
    assert(m.pollFirstIfTriggered(Long.MinValue) eq null)

    m.insert(0L, 10L, cb0, out)
    assert(out(0) eq null)
    m.insert(0L, 30L, cb1, out)
    assert(out(0) eq null)
    m.insert(0L, 0L, cb2, out)
    assert(out(0) eq null)
    m.insert(0L, 20L, cb3, out)
    assertEquals(out(0), cb2)
    assert(m.pollFirstIfTriggered(-1L) eq null)
    assert(m.pollFirstIfTriggered(0L) eq null)
    assertEquals(m.pollFirstIfTriggered(10L), cb0)
    assert(m.pollFirstIfTriggered(10L) eq null)
    assertEquals(m.pollFirstIfTriggered(20L), cb3)
    assert(m.pollFirstIfTriggered(20L) eq null)
    assertEquals(m.pollFirstIfTriggered(30L), cb1)
    assert(m.pollFirstIfTriggered(30L) eq null)
    assert(m.pollFirstIfTriggered(Long.MaxValue) eq null)
  }

  testUnit("correctly insert / remove (cancel)") {
    val m = new TimerHeap
    val out = new Array[Right[Nothing, Unit] => Unit](1)
    val r0 = m.insert(0L, 1L, cb0, out)
    assert(out(0) eq null)
    val r1 = m.insert(0L, 2L, cb1, out)
    assert(out(0) eq null)
    val r5 = m.insert(0L, 6L, cb5, out)
    assert(out(0) eq null)
    val r4 = m.insert(0L, 5L, cb4, out)
    assert(out(0) eq null)
    val r2 = m.insert(0L, 3L, cb2, out)
    assert(out(0) eq null)
    val r3 = m.insert(0L, 4L, cb3, out)
    assert(out(0) eq null)

    assertEquals(m.peekFirstQuiescent(), cb0)
    assertEquals(m.peekFirstTriggerTime(), 1L)
    r0.run()
    assertEquals(m.peekFirstTriggerTime(), 2L)
    assertEquals(m.peekFirstQuiescent(), cb1)
    assertEquals(m.pollFirstIfTriggered(Long.MaxValue), cb1)
    assertEquals(m.peekFirstQuiescent(), cb2)
    assertEquals(m.peekFirstTriggerTime(), 3L)
    r1.run() // NOP
    r3.run()
    m.packIfNeeded()
    assertEquals(m.peekFirstQuiescent(), cb2)
    assertEquals(m.peekFirstTriggerTime(), 3L)
    assertEquals(m.pollFirstIfTriggered(Long.MaxValue), cb2)
    assertEquals(m.peekFirstQuiescent(), cb4)
    assertEquals(m.peekFirstTriggerTime(), 5L)
    assertEquals(m.pollFirstIfTriggered(Long.MaxValue), cb4)
    assertEquals(m.peekFirstQuiescent(), cb5)
    assertEquals(m.peekFirstTriggerTime(), 6L)
    r2.run()
    r5.run()
    m.packIfNeeded()
    assert(m.peekFirstQuiescent() eq null)
    assertEquals(m.peekFirstTriggerTime(), Long.MinValue)
    assert(m.pollFirstIfTriggered(Long.MaxValue) eq null)
    r4.run() // NOP
    m.packIfNeeded()
    assert(m.pollFirstIfTriggered(Long.MaxValue) eq null)
  }

  testUnit("behave correctly when nanoTime wraps around") {
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
    assertEquals(triggered, nonCanceled)
  }

}
