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

import cats.effect.tracing.{IOTrace, StackTraceFrame}
import cats.effect.internals.TracingPlatform.traceBufferSize

/**
 * INTERNAL API — Holds state related to the execution of
 * an IO and should be threaded across multiple invocations
 * of the run-loop associated with the same fiber.
 */
final private[effect] class IOContext private () {

  private[this] val frames: RingBuffer[StackTraceFrame] = new RingBuffer(traceBufferSize)
  private[this] var captured: Int = 0
  private[this] var omitted: Int = 0

  def pushFrame(fr: StackTraceFrame): Unit = {
    captured += 1
    if (frames.push(fr) != null) omitted += 1
  }

  def trace(): IOTrace =
    IOTrace(frames.toList, captured, omitted)

}

object IOContext {
  def apply(): IOContext =
    new IOContext
}
