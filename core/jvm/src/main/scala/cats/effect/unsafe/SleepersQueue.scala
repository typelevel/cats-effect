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

import scala.collection.mutable.PriorityQueue

private final class SleepersQueue private () {
  private[this] val queue: PriorityQueue[SleepCallback] = PriorityQueue.empty
  private[this] var count: Int = 0

  def isEmpty: Boolean =
    count == 0

  def head(): SleepCallback =
    queue.head

  def +=(scb: SleepCallback): Unit = {
    queue += scb
    count += 1
  }

  def popHead(): Unit = {
    queue.dequeue()
    count -= 1
    ()
  }
}

private object SleepersQueue {
  def empty: SleepersQueue = new SleepersQueue()
}
