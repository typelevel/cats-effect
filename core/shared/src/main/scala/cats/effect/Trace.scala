/*
 * Copyright 2020-2024 Typelevel
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

package cats.effect

import cats.effect.tracing.{RingBuffer, Tracing}

final class Trace private (frames: List[StackTraceElement]) {

  private[this] def renderStackTraceElement(ste: StackTraceElement): String = {
    s"${ste.getClassName}.${ste.getMethodName} (${ste.getFileName}:${ste.getLineNumber})"
  }

  def pretty: String = {
    val TurnRight = "╰"
    val Junction = "├"
    var captured = 0
    val indexedRenderedStackTrace = frames.map { frame =>
      val res = renderStackTraceElement(frame) -> captured
      captured += 1
      res
    }

    val acc0 = s"Trace: ${captured} frames captured\n"
    val acc1 = indexedRenderedStackTrace
      .map {
        case (tag, index) =>
          if (index == 0) s"$Junction $tag"
          else if (index == captured - 1) s"$TurnRight $tag"
          else s"$Junction $tag"
      }
      .mkString("\n")

    acc0 + acc1
  }

  def compact: String = frames.map(renderStackTraceElement).mkString(", ")

  def toList: List[StackTraceElement] = frames

  override def toString: String = compact
}

private object Trace {
  def apply(events: RingBuffer): Trace =
    new Trace(Tracing.getFrames(events))
}
