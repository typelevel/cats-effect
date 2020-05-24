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

import cats.effect.internals.TracingPlatformFast.{frameCache, localTracingMode}
import cats.effect.IO
import cats.effect.IO.Trace
import cats.effect.tracing.{TraceFrame, StackTraceLine, TracingMode}

private[effect] object IOTracing {

  def apply[A](source: IO[A], clazz: Class[_]): IO[A] =
//    val mode = localTracingMode.get()
//    if (mode == 1) {
//      Trace(source, buildCachedFrame(source.getClass, clazz))
//    } else if (mode == 2) {
//      Trace(source, buildFrame(source.getClass))
//    } else {
//      source
//    }
    Trace(source, buildCachedFrame(source.getClass, clazz))

  def locallyTraced[A](source: IO[A], newMode: TracingMode): IO[A] =
    IO.suspend {
      val oldMode = localTracingMode.get()
      localTracingMode.set(newMode.tag)

      // In the event of cancellation, the tracing mode will be reset
      // when the thread grabs a new task to run (via Async).
      source.redeemWith(
        e =>
          IO.suspend {
            localTracingMode.set(oldMode)
            IO.raiseError(e)
          },
        a =>
          IO.suspend {
            localTracingMode.set(oldMode)
            IO.pure(a)
          }
      )
    }

  def getLocalTracingMode(): TracingMode =
    TracingMode.fromInt(localTracingMode.get())

  def setLocalTracingMode(mode: TracingMode): Unit =
    localTracingMode.set(mode.tag)

  private def buildCachedFrame(sourceClass: Class[_], keyClass: Class[_]): TraceFrame = {
    val cachedFr = frameCache.get(keyClass).asInstanceOf[TraceFrame]
    if (cachedFr eq null) {
      val fr = buildFrame(sourceClass)
      frameCache.put(keyClass, fr)
      fr
    } else {
      cachedFr
    }
  }

  private def buildFrame(sourceClass: Class[_]): TraceFrame = {
    // TODO: proper trace calculation
    val line = new Throwable().getStackTrace.toList
      .map(StackTraceLine.fromStackTraceElement)
      .find(l => !classBlacklist.exists(b => l.className.startsWith(b)))
      .headOption

    TraceFrame(sourceClass.getSimpleName, line)
  }

  private val classBlacklist = List(
    "cats.effect.",
    "cats.",
    "sbt.",
    "java.",
    "sun.",
    "scala."
  )

}
