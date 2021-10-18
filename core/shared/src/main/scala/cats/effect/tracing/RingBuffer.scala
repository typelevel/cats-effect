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

package cats.effect.tracing

private[effect] final class RingBuffer private (logSize: Int) {

  private[this] val length = 1 << logSize
  private[this] val mask = length - 1

  private[this] val buffer: Array[TracingEvent] = new Array(length)
  private[this] var index: Int = 0

  def push(te: TracingEvent): Unit = {
    val idx = index & mask
    buffer(idx) = te
    index += 1
  }

  /**
   * Returns a list in reverse order of insertion.
   */
  def toList(): List[TracingEvent] = {
    var result = List.empty[TracingEvent]
    val msk = mask
    val idx = index
    val start = math.max(idx - length, 0)
    val end = idx
    var i = start
    while (i < end) {
      result ::= buffer(i & msk)
      i += 1
    }
    result
  }
}

private[effect] object RingBuffer {
  def empty(logSize: Int): RingBuffer = new RingBuffer(logSize)
}
