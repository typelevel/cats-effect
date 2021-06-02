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

/**
 * Global cache for trace frames. Keys are references to lambda classes.
 * Should converge to the working set of traces very quickly for hot code paths.
 */
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
    val callSite = CallSite.generateCallSite()
    TracingEvent.CallSite(callSite)
  }

  private[this] val runLoopFilter: Array[String] = Array("cats.effect.", "scala.runtime.")

  def augmentThrowable(t: Throwable, events: RingBuffer): Unit = {
    def filter(ste: StackTraceElement): Boolean = {
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
        if (!filter(frame)) {
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
          val suffix = events
            .toList
            .collect { case ev: TracingEvent.CallSite if ev.callSite ne null => ev.callSite }
            .toArray
          t.setStackTrace(prefix ++ suffix)
        }
      }
    }
  }
}
