/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals

import cats.effect.tracing.IOEvent
import munit.FunSuite

class IOContextTests extends FunSuite {

  val traceBufferSize: Int = 1 << cats.effect.internals.TracingPlatform.traceBufferLogSize
  val stackTrace = new Throwable().getStackTrace.toList

  test("push traces") {
    val ctx = new IOContext()

    val t1 = IOEvent.StackTrace(stackTrace)
    val t2 = IOEvent.StackTrace(stackTrace)

    ctx.pushEvent(t1)
    ctx.pushEvent(t2)

    val trace = ctx.trace()
    assertEquals(trace.events, List(t1, t2))
    assertEquals(trace.captured, 2)
    assertEquals(trace.omitted, 0)
  }

  test("track omitted frames") {
    val ctx = new IOContext()

    for (_ <- 0 until (traceBufferSize + 10)) {
      ctx.pushEvent(IOEvent.StackTrace(stackTrace))
    }

    val trace = ctx.trace()
    assertEquals(trace.events.length, traceBufferSize)
    assertEquals(trace.captured, (traceBufferSize + 10))
    assertEquals(trace.omitted, 10)
  }

}
