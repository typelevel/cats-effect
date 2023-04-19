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
import org.openjdk.jcstress.infra.results.JJJ_Result

@JCStressTest
@State
@Description("TimerSkipList pollFirstIfTriggered/pollFirstIfTriggered race")
@Outcomes(
  Array(
    new JOutcome(
      id = Array("1, 0, 0"),
      expect = ACCEPTABLE_INTERESTING,
      desc = "pollFirst1 won"),
    new JOutcome(
      id = Array("0, 1, 0"),
      expect = ACCEPTABLE_INTERESTING,
      desc = "pollFirst2 won")
  ))
class SkipListTest4 {

  private[this] val headCb =
    newCallback()

  private[this] val secondCb =
    newCallback()

  private[this] val m = {
    val m = new TimerSkipList
    // head is 1025L:
    m.insertTlr(now = 1L, delay = 1024L, callback = headCb)
    // second is 1026L:
    m.insertTlr(now = 2L, delay = 1024L, callback = secondCb)
    for (i <- 3 to 128) {
      m.insertTlr(now = i.toLong, delay = 1024L, callback = newCallback())
    }
    m
  }

  @Actor
  def pollFirst1(r: JJJ_Result): Unit = {
    val cb = m.pollFirstIfTriggered(now = 2048L)
    r.r1 = if (cb eq headCb) 1L else if (cb eq secondCb) 0L else -1L
  }

  @Actor
  def pollFirst2(r: JJJ_Result): Unit = {
    val cb = m.pollFirstIfTriggered(now = 2048L)
    r.r2 = if (cb eq headCb) 1L else if (cb eq secondCb) 0L else -1L
  }

  @Arbiter
  def arbiter(r: JJJ_Result): Unit = {
    val otherCb = m.pollFirstIfTriggered(now = 2048L)
    r.r3 = if (otherCb eq headCb) -1L else if (otherCb eq secondCb) -1L else 0L
  }

  private[this] final def newCallback(): Right[Nothing, Unit] => Unit = {
    new Function1[Right[Nothing, Unit], Unit] with Serializable {
      final override def apply(r: Right[Nothing, Unit]): Unit = ()
    }
  }
}
