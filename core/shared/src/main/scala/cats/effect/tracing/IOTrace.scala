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

  def compact: String = {
    val TurnRight = "╰"
    val Junction = "├"

    def renderStackTraceElement(ste: StackTraceElement): String = {
      val className = demangleClassName(ste.getClassName)
      val methodName = demangleMethodName(ste.getMethodName)
      s"$className.$methodName (${ste.getFileName}:${ste.getLineNumber})"
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

  def compactPrint: IO[Unit] =
    IO(System.err.println(compact))

  def pretty: String = {
    val acc0 = s"IOTrace: $captured frames captured, $omitted omitted\n"
    val acc1 = acc0 + loop("", 0, true, frames.toList)
    acc1
  }

  def prettyPrint: IO[Unit] =
    IO(System.err.println(pretty))

  private def loop(acc: String, indent: Int, init: Boolean, rest: List[StackTraceFrame]): String = {
    val TurnRight = "╰"
    val InverseTurnRight = "╭"
    val Junction = "├"
    val Line = "│"

    def renderStackTraceElement(ste: StackTraceElement, last: Boolean): String = {
      val className = demangleClassName(ste.getClassName)
      val methodName = demangleMethodName(ste.getMethodName)

      val junc = if (last) TurnRight else Junction

      Line + "  " + junc + s" $className.$methodName (${ste.getFileName}:${ste.getLineNumber})\n"
    }

    rest match {
      case k :: ks => {
        val acc2 = if (init) InverseTurnRight + s" ${k.tag.name}\n" else Junction + s" ${k.tag.name}\n"
        val innerLines = k.stackTrace
          .drop(stackTraceIgnoreLines)
          .zipWithIndex
          .map {
            case (ste, i) => renderStackTraceElement(ste, i == k.stackTrace.length - 1)
          }
          .mkString

        loop(acc + acc2 + innerLines + Line + "\n", indent, false, ks)
      }
      case Nil => acc
    }
  }

  private def demangleClassName(className: String): String =
    className.replaceAll("\\$", "")

  private def demangleMethodName(methodName: String): String =
    anonfunRegex.findFirstMatchIn(methodName) match {
      case Some(mat) => mat.group(1)
      case None      => methodName
    }

}

private[effect] object IOTrace {
  final val anonfunRegex = "^\\$+anonfun\\$+(.+)\\$+\\d+$".r

  // Number of lines to drop from the top of the stack trace
  final val stackTraceIgnoreLines = 3

  final val stackTraceFilter = List(
    "cats.effect.",
    "cats.",
    "sbt.",
    "java.",
    "sun.",
    "scala."
  )
}
