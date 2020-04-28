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

import cats.effect.tracing.{IOTrace, TraceFrame}

/**
 * IOContext holds state related to the execution of an IO and
 * should be threaded across multiple invocations of the run-loop
 * for the same fiber.
 */
final private[effect] class IOContext private () {

  // We have to use a volatile here because of IOBracket implementation
  // and how it invokes a new run-loop "asynchronously."
  // Ideally we could use a mutable, ring buffer here.
  @volatile var frames: Vector[TraceFrame] = Vector.empty

  def pushFrame(fr: TraceFrame): Unit = {
    // Accessed from at most one thread at a time,
    // so no race condition will occur
    val currFrames = frames
    if (currFrames.length >= 1000) {
      frames = fr +: currFrames.dropRight(1)
    } else {
      frames = fr +: currFrames
    }
  }

  def getTrace: IOTrace =
    IOTrace(frames)

}

object IOContext {
  def newContext: IOContext =
    new IOContext
}
