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

import java.util.concurrent.ConcurrentHashMap

import cats.effect.IO
import cats.effect.tracing.{TraceFrame, TraceLine, TracingMode}

private[effect] object IOTracing {

  def apply[A](source: IO[A], clazz: Class[_]): IO[A] =
    localTracingMode.get() match {
      case TracingMode.Disabled => source
      case TracingMode.Rabbit   => IO.Trace(source, buildCachedFrame(source, clazz))
      case TracingMode.Slug     => IO.Trace(source, buildFrame(source))
    }

  def locallyTraced[A](source: IO[A], newMode: TracingMode): IO[A] =
    IO.suspend {
      val oldMode = localTracingMode.get()
      localTracingMode.set(newMode)

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
    localTracingMode.get()

  def setLocalTracingMode(mode: TracingMode): Unit =
    localTracingMode.set(mode)

  private def buildCachedFrame(source: IO[Any], clazz: Class[_]): TraceFrame = {
    val cachedFr = frameCache.get(clazz)
    if (cachedFr eq null) {
      val fr = buildFrame(source)
      frameCache.put(clazz, fr)
      fr
    } else {
      cachedFr
    }
  }

  private def buildFrame(source: IO[Any]): TraceFrame = {
    // TODO: proper trace calculation
    val line = new Throwable().getStackTrace.toList
      .map(TraceLine.fromStackTraceElement)
      .find(l => !classBlacklist.exists(b => l.className.startsWith(b)))
      .headOption

    val op = source match {
      case _: IO.Map[_, _]  => "map"
      case _: IO.Bind[_, _] => "bind"
      case _: IO.Async[_]   => "async"
      case _                => "unknown"
    }

    TraceFrame(op, line)
  }

  /**
   * Cache for trace frames. Keys are references to:
   * - lambda classes
   */
  private val frameCache: ConcurrentHashMap[Class[_], TraceFrame] = new ConcurrentHashMap[Class[_], TraceFrame]()

  private val localTracingMode: ThreadLocal[TracingMode] = new ThreadLocal[TracingMode] {
    override def initialValue(): TracingMode = TracingMode.Rabbit
  }

  private val classBlacklist = List(
    "cats.effect.",
    "sbt.",
    "java.",
    "sun.",
    "scala."
  )

}
