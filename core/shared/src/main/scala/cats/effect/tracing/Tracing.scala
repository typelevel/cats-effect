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
package tracing

import scala.collection.mutable.ArrayBuffer

import Platform.static

private[effect] final class Tracing

private[effect] object Tracing extends TracingPlatform {

  import TracingConstants._

  @static private[this] final val TurnRight = "╰"
  // private[this] final val InverseTurnRight = "╭"
  @static private[this] final val Junction = "├"
  // private[this] final val Line = "│"

  @static private[tracing] def buildEvent(): TracingEvent = {
    new TracingEvent.StackTrace()
  }

  @static private[this] final val runLoopFilter: Array[String] =
    Array(
      "cats.effect.",
      "scala.runtime.",
      "scala.scalajs.runtime.",
      "scala.scalanative.runtime.")

  @static private[tracing] final val stackTraceClassNameFilter: Array[String] = Array(
    "cats.",
    "sbt.",
    "java.",
    "jdk.",
    "sun.",
    "scala.",
    "org.scalajs."
  )

  @static private[tracing] def combineOpAndCallSite(
      methodSite: StackTraceElement,
      callSite: StackTraceElement): StackTraceElement = {
    val methodSiteMethodName = methodSite.getMethodName
    val op = decodeMethodName(methodSiteMethodName)

    new StackTraceElement(
      op + " @ " + callSite.getClassName,
      callSite.getMethodName,
      callSite.getFileName,
      callSite.getLineNumber
    )
  }

  @static private[tracing] def isInternalClass(className: String): Boolean = {
    var i = 0
    val len = stackTraceClassNameFilter.length
    while (i < len) {
      if (className.startsWith(stackTraceClassNameFilter(i)))
        return true
      i += 1
    }
    false
  }

  @static private[this] def getOpAndCallSite(
      stackTrace: Array[StackTraceElement]): StackTraceElement = {
    val len = stackTrace.length
    var idx = 1
    while (idx < len) {
      val methodSite = stackTrace(idx - 1)
      val callSite = stackTrace(idx)
      val callSiteClassName = callSite.getClassName
      val callSiteMethodName = callSite.getMethodName
      val callSiteFileName = callSite.getFileName

      if (callSiteClassName == "cats.effect.IOFiber" && callSiteMethodName == "run")
        return null // short-circuit, effective end of stack

      if (!applyStackTraceFilter(callSiteClassName, callSiteMethodName, callSiteFileName))
        return combineOpAndCallSite(methodSite, callSite)

      idx += 1
    }

    null
  }

  @static def augmentThrowable(
      enhancedExceptions: Boolean,
      t: Throwable,
      events: RingBuffer): Unit = {
    def applyRunLoopFilter(ste: StackTraceElement): Boolean = {
      val name = ste.getClassName
      var i = 0
      val len = runLoopFilter.length
      while (i < len) {
        if (name.startsWith(runLoopFilter(i))) {
          return true
        }
        i += 1
      }

      false
    }

    def dropRunLoopFrames(frames: Array[StackTraceElement]): Array[StackTraceElement] = {
      val buffer = ArrayBuffer.empty[StackTraceElement]
      var i = 0
      val len = frames.length
      while (i < len) {
        val frame = frames(i)
        if (!applyRunLoopFilter(frame)) {
          buffer += frame
        } else {
          return buffer.toArray
        }
        i += 1
      }

      buffer.toArray
    }

    if (isStackTracing && enhancedExceptions) {
      val stackTrace = t.getStackTrace
      if (!stackTrace.isEmpty) {
        val augmented = stackTrace.last.getClassName.indexOf('@') != -1
        if (!augmented) {
          val prefix = dropRunLoopFrames(stackTrace)
          val suffix = getFrames(events).toArray

          t.setStackTrace(prefix ++ suffix)
        }
      }
    }
  }

  @static def getFrames(events: RingBuffer): List[StackTraceElement] =
    events
      .toList()
      .collect { case ev: TracingEvent.StackTrace => getOpAndCallSite(ev.getStackTrace) }
      .filter(_ ne null)

  @static def prettyPrint(trace: Trace): String = {
    val frames = trace.toList

    frames
      .zipWithIndex
      .map {
        case (frame, index) =>
          val junc = if (index == frames.length - 1) TurnRight else Junction
          s" $junc $frame"
      }
      .mkString(System.lineSeparator())
  }

  @static def captureTrace(runnable: Runnable): Option[(Runnable, Trace)] = {
    runnable match {
      case f: IOFiber[_] if f.isDone => None
      case f: IOFiber[_] => Some(runnable -> f.captureTrace())
      case _ => Some(runnable -> Trace(RingBuffer.empty(1)))
    }
  }
}
