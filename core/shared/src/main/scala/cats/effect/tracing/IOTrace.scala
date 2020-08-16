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

import scala.reflect.NameTransformer

final case class IOTrace(events: List[IOEvent], captured: Int, omitted: Int) {

  import IOTrace._

  def printFiberTrace(options: PrintingOptions = PrintingOptions.Default): IO[Unit] =
    IO(System.err.println(showFiberTrace(options)))

  def showFiberTrace(options: PrintingOptions = PrintingOptions.Default): String = {
    val TurnRight = "╰"
    val InverseTurnRight = "╭"
    val Junction = "├"
    val Line = "│"

    val acc0 = s"IOTrace: $captured frames captured\n"
    if (options.showFullStackTraces) {
      val stackTraces = events.collect { case e: IOEvent.StackTrace => e }

      val acc1 = stackTraces.zipWithIndex
        .map {
          case (st, index) =>
            val tag = getOpAndCallSite(st.stackTrace)
              .map {
                case (methodSite, _) => NameTransformer.decode(methodSite.getMethodName)
              }
              .getOrElse("(...)")
            val op = if (index == 0) s"$InverseTurnRight $tag\n" else s"$Junction $tag\n"
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

      val acc2 = if (omitted > 0) {
        "\n" + TurnRight + s" ... ($omitted frames omitted)\n"
      } else "\n" + TurnRight + "\n"

      acc0 + acc1 + acc2
    } else {
      val acc1 = events.zipWithIndex
        .map {
          case (event, index) =>
            val junc = if (index == events.length - 1 && omitted == 0) TurnRight else Junction
            val message = event match {
              case ev: IOEvent.StackTrace => {
                getOpAndCallSite(ev.stackTrace)
                  .map {
                    case (methodSite, callSite) =>
                      val loc = renderStackTraceElement(callSite)
                      val op = NameTransformer.decode(methodSite.getMethodName)
                      s"$op @ $loc"
                  }
                  .getOrElse("(...)")
              }
            }
            s" $junc $message"
        }
        .mkString(acc0, "\n", "")

      val acc2 = if (omitted > 0) {
        acc1 + "\n " + TurnRight + s" ... ($omitted frames omitted)"
      } else acc1

      acc2 + "\n"
    }
  }
}

private[effect] object IOTrace {

  def getOpAndCallSite(frames: List[StackTraceElement]): Option[(StackTraceElement, StackTraceElement)] =
    frames
      .sliding(2)
      .collect {
        case a :: b :: Nil => (a, b)
      }
      .find {
        case (_, callSite) => !stackTraceFilter.exists(callSite.getClassName.startsWith(_))
      }

  private def renderStackTraceElement(ste: StackTraceElement): String = {
    val methodName = demangleMethod(ste.getMethodName)
    s"${ste.getClassName}.$methodName (${ste.getFileName}:${ste.getLineNumber})"
  }

  private def demangleMethod(methodName: String): String =
    anonfunRegex.findFirstMatchIn(methodName) match {
      case Some(mat) => mat.group(1)
      case None      => methodName
    }

  private[this] val anonfunRegex = "^\\$+anonfun\\$+(.+)\\$+\\d+$".r

  private[this] val stackTraceFilter = List(
    "cats.effect.",
    "cats.",
    "sbt.",
    "java.",
    "sun.",
    "scala."
  )
}
