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

final case class IOTrace(events: List[IOEvent], captured: Int, omitted: Int) {

  import IOTrace._

  def printFiberTrace(options: PrintingOptions = PrintingOptions.Default): IO[Unit] =
    IO(System.err.println(showFiberTrace(options)))

  def showFiberTrace(options: PrintingOptions = PrintingOptions.Default): String = {
    val TurnRight = "╰"
    val InverseTurnRight = "╭"
    val Junction = "├"
    val Line = "│"

    if (options.showFullStackTraces) {
      val stackTraces = events.collect { case e: IOEvent.StackTrace => e }

      val header = s"IOTrace: $captured frames captured, $omitted omitted\n"
      val body = stackTraces.zipWithIndex
        .map {
          case (st, index) =>
            val nameTag = tagToName(st.tag)
            val op = if (index == 0) s"$InverseTurnRight $nameTag\n" else s"$Junction $nameTag\n"
            val relevantLines = st.stackTrace
              .drop(options.ignoreStackTraceLines)
              .take(options.maxStackTraceLines)
            val lines = relevantLines.zipWithIndex
              .map {
                case (ste, i) =>
                  val junc = if (i == relevantLines.length - 1) TurnRight else Junction
                  val codeLine = renderStackTraceElement(ste)
                  s"$Line  $junc $codeLine"
              }
              .mkString("", "\n", "\n")

            s"$op$lines$Line"
        }
        .mkString("\n")

      header + body
    } else {
      val acc0 = s"IOTrace: $captured frames captured, $omitted omitted\n"
      val acc1 = events.zipWithIndex
        .map {
          case (event, index) =>
            val junc = if (index == events.length - 1) TurnRight else Junction
            val message = event match {
              case ev: IOEvent.StackTrace => {
                val first = bestTraceElement(ev.stackTrace)
                val nameTag = tagToName(ev.tag)
                val codeLine = first.map(renderStackTraceElement).getOrElse("(...)")
                s"$nameTag at $codeLine"
              }
            }
            s" $junc $message"
        }
        .mkString(acc0, "\n", "\n")

      acc1
    }
  }
}

private[effect] object IOTrace {

  // Number of lines to drop from the top of the stack trace
  def stackTraceIgnoreLines = 3

  private[this] val anonfunRegex = "^\\$+anonfun\\$+(.+)\\$+\\d+$".r

  private[this] val stackTraceFilter = List(
    "cats.effect.",
    "cats.",
    "sbt.",
    "java.",
    "sun.",
    "scala."
  )

  private def renderStackTraceElement(ste: StackTraceElement): String = {
    val methodName = demangleMethod(ste.getMethodName)
    s"${ste.getClassName}.$methodName (${ste.getFileName}:${ste.getLineNumber})"
  }

  private def bestTraceElement(frames: List[StackTraceElement]): Option[StackTraceElement] =
    frames.dropWhile(l => stackTraceFilter.exists(b => l.getClassName.startsWith(b))).headOption

  private def demangleMethod(methodName: String): String =
    anonfunRegex.findFirstMatchIn(methodName) match {
      case Some(mat) => mat.group(1)
      case None      => methodName
    }

  private def tagToName(tag: Int): String =
    tag match {
      case 0 => "pure"
      case 1 => "delay"
      case 2 => "suspend"
      case 3 => "flatMap"
      case 4 => "map"
      case 5 => "async"
      case 6 => "asyncF"
      case 7 => "cancelable"
      case 8 => "raiseError"
      case _ => "???"
    }
}
