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

import org.openjdk.jcstress.annotations.{Outcome => JOutcome, Ref => _, _}
import org.openjdk.jcstress.annotations.Expect._
import org.openjdk.jcstress.annotations.Outcome.Outcomes
import org.openjdk.jcstress.infra.results.JJ_Result

@JCStressTest
@State
@Description("TimerSkipList: 3-way insert/pollFirstIfTriggered/cancel race")
@Outcomes(
  Array(
    new JOutcome(
      id = Array("3, 2"),
      expect = ACCEPTABLE_INTERESTING,
      desc = "(cancel | insert), pollFirst"),
    new JOutcome(
      id = Array("1, 3"),
      expect = ACCEPTABLE_INTERESTING,
      desc = "pollFirst, (cancel | insert) OR insert, pollFirst, cancel"),
    new JOutcome(
      id = Array("2, 3"),
      expect = ACCEPTABLE_INTERESTING,
      desc = "cancel, pollFirst, insert")
  ))
class SkipListTest6 {

  /*
   * Concurrently (1) cancel the first item,
   * (2) insert a new one right after it, and
   * (3) deque the first item.
   */

  private[this] val secondCb =
    newCallback()

  private[this] val m = {
    val m = new TimerSkipList
    m.insertTlr(now = 2L, delay = 1024L, callback = secondCb)
    for (i <- 3 to 128) {
      m.insertTlr(now = i.toLong, delay = 1024L, callback = newCallback())
    }
    m
  }

  private[this] val headCb =
    newCallback()

  private[this] val headCanceller =
    m.insertTlr(now = 1L, delay = 1024L, callback = headCb)

  private[this] val newCb =
    newCallback()

  @Actor
  def cancel(): Unit = {
    headCanceller.run()
  }

  @Actor
  def insert(): Unit = {
    // head is 1025L now, we insert another 1025L:
    m.insertTlr(now = 1L, delay = 1024L, callback = newCb)
    ()
  }

  @Actor
  def pollFirst(r: JJ_Result): Unit = {
    r.r1 = longFromCb(m.pollFirstIfTriggered(now = 2048L))
  }

  @Arbiter
  def arbiter(r: JJ_Result): Unit = {
    r.r2 = longFromCb(m.pollFirstIfTriggered(now = 2048L))
  }

  private[this] final def longFromCb(cb: Right[Nothing, Unit] => Unit): Long = {
    if (cb eq headCb) 1L
    else if (cb eq secondCb) 2L
    else if (cb eq newCb) 3L
    else -1L
  }

  private[this] final def newCallback(): Right[Nothing, Unit] => Unit = {
    new Function1[Right[Nothing, Unit], Unit] with Serializable {
      final override def apply(r: Right[Nothing, Unit]): Unit = ()
    }
  }
}
