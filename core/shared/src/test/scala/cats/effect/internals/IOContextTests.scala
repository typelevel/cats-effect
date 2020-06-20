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

import cats.effect.tracing.{TraceFrame, TraceTag}
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuite

class IOContextTests extends AnyFunSuite with Matchers {

  val maxTraceDepth: Int = cats.effect.internals.TracingPlatform.maxTraceDepth
  val throwable = new Throwable()

  test("push traces") {
    val ctx = IOContext()

    val t1 = TraceFrame(TraceTag.Pure, throwable)
    val t2 = TraceFrame(TraceTag.Suspend, throwable)

    ctx.pushFrame(t1)
    ctx.pushFrame(t2)

    val trace = ctx.trace
    trace.frames shouldBe List(t1, t2)
    trace.captured shouldBe 2
    trace.omitted shouldBe 0
  }

  test("track omitted frames") {
    val ctx = IOContext()

    for (_ <- 0 until (maxTraceDepth + 10)) {
      ctx.pushFrame(TraceFrame(TraceTag.Pure, throwable))
    }

    val trace = ctx.trace()
    trace.frames.length shouldBe maxTraceDepth
    trace.captured shouldBe (maxTraceDepth + 10)
    trace.omitted shouldBe 10
  }

  test("reset tracing") {
    val ctx = IOContext()

    val t1 = TraceFrame(TraceTag.Pure, throwable)
    val t2 = TraceFrame(TraceTag.Suspend, throwable)

    ctx.pushFrame(t1)
    ctx.pushFrame(t2)

    ctx.resetTrace()

    val trace = ctx.trace()
    trace.frames shouldBe List()
    trace.captured shouldBe 0
    trace.omitted shouldBe 0
  }

  test("track tracing regions") {
    val ctx = IOContext()

    ctx.activeTraces() shouldBe 0

    ctx.enterTrace()
    ctx.activeTraces() shouldBe 1
    ctx.enterTrace()
    ctx.activeTraces() shouldBe 2

    ctx.exitTrace()
    ctx.activeTraces() shouldBe 1
    ctx.exitTrace()
    ctx.activeTraces() shouldBe 0
  }
}
