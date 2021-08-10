/*
 * Copyright 2020-2021 Typelevel
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

import scala.collection.mutable.ArrayBuffer
import scala.reflect.NameTransformer

private[effect] object Tracing extends ClassValue[TracingEvent] {

  import TracingConstants._

  override protected def computeValue(cls: Class[_]): TracingEvent = {
    buildEvent()
  }

  def calculateTracingEvent(cls: Class[_]): TracingEvent = {
    if (isCachedStackTracing) {
      get(cls)
    } else if (isFullStackTracing) {
      buildEvent()
    } else {
      null
    }
  }

  private[this] def buildEvent(): TracingEvent = {
    new TracingEvent.StackTrace()
  }

  private[this] final val runLoopFilter: Array[String] = Array("cats.effect.", "scala.runtime.")

  private[this] final val stackTraceFilter: Array[String] = Array(
    "cats.effect.",
    "cats.",
    "sbt.",
    "java.",
    "sun.",
    "scala."
  )

  private[this] def applyStackTraceFilter(callSiteClassName: String): Boolean = {
    val len = stackTraceFilter.length
    var idx = 0
    while (idx < len) {
      if (callSiteClassName.startsWith(stackTraceFilter(idx))) {
        return true
      }

      idx += 1
    }

    false
  }

  private[this] def getOpAndCallSite(
      stackTrace: Array[StackTraceElement]): StackTraceElement = {
    val len = stackTrace.length
    var idx = 1
    while (idx < len) {
      val callSite = stackTrace(idx)
      val callSiteClassName = callSite.getClassName

      if (!applyStackTraceFilter(callSiteClassName)) {
        var mIdx = idx - 1
        var methodSiteMethodName = stackTrace(mIdx).getMethodName
        while ((methodSiteMethodName == "<init>" || methodSiteMethodName == "<clinit>") && mIdx > 0) {
          mIdx -= 1
          methodSiteMethodName = stackTrace(mIdx).getMethodName
        }

        val op = NameTransformer.decode(methodSiteMethodName)

        return new StackTraceElement(
          op + " @ " + callSiteClassName,
          callSite.getMethodName,
          callSite.getFileName,
          callSite.getLineNumber
        )
      }

      idx += 1
    }

    null
  }

  def augmentThrowable(enhancedExceptions: Boolean, t: Throwable, events: RingBuffer): Unit = {
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

  def getFrames(events: RingBuffer): List[StackTraceElement] =
    events
      .toList
      .collect { case ev: TracingEvent.StackTrace => getOpAndCallSite(ev.getStackTrace) }
      .filter(_ ne null)

}
