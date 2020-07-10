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

import cats.effect.tracing.StackTraceFrame
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuite

class IOContextTests extends AnyFunSuite with Matchers {

  val traceBufferSize: Int = cats.effect.internals.TracingPlatform.traceBufferSize
  val throwable = new Throwable()

  test("push traces") {
    val ctx = new IOContext()

    val t1 = StackTraceFrame(0, throwable)
    val t2 = StackTraceFrame(1, throwable)

    ctx.pushFrame(t1)
    ctx.pushFrame(t2)

    val trace = ctx.trace
    trace.frames shouldBe List(t1, t2)
    trace.captured shouldBe 2
    trace.omitted shouldBe 0
  }

  test("track omitted frames") {
    val ctx = new IOContext()

    for (_ <- 0 until (traceBufferSize + 10)) {
      ctx.pushFrame(StackTraceFrame(0, throwable))
    }

    val trace = ctx.trace()
    trace.frames.length shouldBe traceBufferSize
    trace.captured shouldBe (traceBufferSize + 10)
    trace.omitted shouldBe 10
  }

}
