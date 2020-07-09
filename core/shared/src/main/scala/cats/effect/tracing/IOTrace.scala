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

package cats.effect.tracing

import cats.effect.IO

final case class IOTrace(frames: List[StackTraceFrame], captured: Int, omitted: Int) {

  import IOTrace._

  def compact(): String = {
    val TurnRight = "╰"
    val Junction = "├"

    def renderStackTraceElement(ste: StackTraceElement): String = {
      val methodName = demangleMethod(ste.getMethodName)
      s"${ste.getClassName}.$methodName (${ste.getFileName}:${ste.getLineNumber})"
    }

    val acc0 = s"IOTrace: $captured frames captured, $omitted omitted\n"
    val acc1 = frames.zipWithIndex.foldLeft(acc0) {
      case (acc, (f, index)) =>
        val junc = if (index == frames.length - 1) TurnRight else Junction
        val first = f.stackTrace.dropWhile(l => stackTraceFilter.exists(b => l.getClassName.startsWith(b))).headOption
        acc + s"  $junc ${f.tag.name} at " + first.map(renderStackTraceElement).getOrElse("(...)") + "\n"
    } + "\n"

    acc1
  }

  def compactPrint(): IO[Unit] =
    IO(System.err.println(compact))

  def pretty(maxStackTracesLines: Int = Int.MaxValue): String = {
    val acc0 = s"IOTrace: $captured frames captured, $omitted omitted\n"
    val acc1 = acc0 + loop("", 0, true, frames, maxStackTracesLines)
    acc1
  }

  def prettyPrint(maxStackTracesLines: Int = Int.MaxValue): IO[Unit] =
    IO(System.err.println(pretty(maxStackTracesLines)))

  private def loop(acc: String,
                   indent: Int,
                   init: Boolean,
                   rest: List[StackTraceFrame],
                   maxStackTraceLines: Int): String = {
    val TurnRight = "╰"
    val InverseTurnRight = "╭"
    val Junction = "├"
    val Line = "│"

    def renderStackTraceElement(ste: StackTraceElement, last: Boolean): String = {
      val methodName = demangleMethod(ste.getMethodName)
      val junc = if (last) TurnRight else Junction
      Line + "  " + junc + s" ${ste.getClassName}.$methodName (${ste.getFileName}:${ste.getLineNumber})\n"
    }

    rest match {
      case k :: ks => {
        val acc2 = if (init) InverseTurnRight + s" ${k.tag.name}\n" else Junction + s" ${k.tag.name}\n"
        val innerLines = k.stackTrace
          .drop(stackTraceIgnoreLines)
          .take(maxStackTraceLines)
          .zipWithIndex
          .map {
            case (ste, i) => renderStackTraceElement(ste, i == k.stackTrace.length - 1)
          }
          .mkString

        loop(acc + acc2 + innerLines + Line + "\n", indent, false, ks, maxStackTraceLines)
      }
      case Nil => acc
    }
  }

}

private[effect] object IOTrace {
  private val anonfunRegex = "^\\$+anonfun\\$+(.+)\\$+\\d+$".r

  // Number of lines to drop from the top of the stack trace
  private val stackTraceIgnoreLines = 3

  private val stackTraceFilter = List(
    "cats.effect.",
    "cats.",
    "sbt.",
    "java.",
    "sun.",
    "scala."
  )

  def demangleMethod(methodName: String): String =
    anonfunRegex.findFirstMatchIn(methodName) match {
      case Some(mat) => mat.group(1)
      case None      => methodName
    }
}
