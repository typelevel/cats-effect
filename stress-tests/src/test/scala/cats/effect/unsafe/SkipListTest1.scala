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
import org.openjdk.jcstress.infra.results.JJJJ_Result

@JCStressTest
@State
@Description("TimerSkipList insert/pollFirstIfTriggered race")
@Outcomes(
  Array(
    new JOutcome(
      id = Array("1024, -9223372036854775679, 1, 0"),
      expect = ACCEPTABLE_INTERESTING,
      desc = "insert won"),
    new JOutcome(
      id = Array("1024, -9223372036854775679, 0, 1"),
      expect = ACCEPTABLE_INTERESTING,
      desc = "pollFirst won")
  ))
class SkipListTest1 {

  private[this] val headCb =
    newCallback()

  private[this] val m = {
    val m = new TimerSkipList
    // head is 1025L:
    m.insertTlr(now = 1L, delay = 1024L, callback = headCb)
    for (i <- 2 to 128) {
      m.insertTlr(now = i.toLong, delay = 1024L, callback = newCallback())
    }
    m
  }

  private[this] val newCb =
    newCallback()

  @Actor
  def insert(r: JJJJ_Result): Unit = {
    // head is 1025L now, we insert 1024L:
    val cancel = m.insertTlr(now = 128L, delay = 896L, callback = newCb).asInstanceOf[m.Node]
    r.r1 = cancel.triggerTime
    r.r2 = cancel.sequenceNum
  }

  @Actor
  def pollFirst(r: JJJJ_Result): Unit = {
    val cb = m.pollFirstIfTriggered(now = 2048L)
    r.r3 = if (cb eq headCb) 0L else if (cb eq newCb) 1L else -1L
  }

  @Arbiter
  def arbiter(r: JJJJ_Result): Unit = {
    val otherCb = m.pollFirstIfTriggered(now = 2048L)
    r.r4 = if (otherCb eq headCb) 0L else if (otherCb eq newCb) 1L else -1L
  }

  private[this] final def newCallback(): Right[Nothing, Unit] => Unit = {
    new Function1[Right[Nothing, Unit], Unit] with Serializable {
      final override def apply(r: Right[Nothing, Unit]): Unit = ()
    }
  }
}
