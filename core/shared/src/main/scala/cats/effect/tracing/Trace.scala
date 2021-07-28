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

import java.io.{ByteArrayOutputStream, PrintStream}
import cats.effect.tracing.TracingEvent.StackTrace

class Trace private (enhancedExceptions: Boolean, events: List[TracingEvent]) {
  private val collector = new StackTrace
  Tracing.augmentThrowable(enhancedExceptions, collector, events)

  override def toString: String = {
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos)
    collector.printStackTrace(ps)
    baos.toString
  }
}

object Trace {

  def apply(enhancedExceptions: Boolean, events: RingBuffer): Trace = {
    new Trace(enhancedExceptions, events.toList.tail)
  }

}
