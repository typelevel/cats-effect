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
import cats.effect.internals.TracingPlatformFast.tracingEnabled

private[effect] object IOTracing {

  def apply[A](source: IO[A], lambdaRef: AnyRef): IO[A] =
    // TODO: consider inlining this conditional at call-sites
    if (tracingEnabled) {
      localTracingMode.get() match {
        case TracingMode.Disabled => source
        case TracingMode.Rabbit   => IO.Trace(source, buildCachedFrame(lambdaRef))
        case TracingMode.Slug     => IO.Trace(source, buildFrame())
      }
    } else {
      source
    }

  def tracedLocally[A](source: IO[A], mode: TracingMode): IO[A] =
    if (tracingEnabled) {
      IO.suspend {
        val old = localTracingMode.get()
        localTracingMode.set(mode)

        // We don't need to reset the status here in the event of cancellation.
        source.attempt.flatMap { e =>
          localTracingMode.set(old)
          IO.fromEither(e)
        }
      }
    } else {
      source
    }

  def getLocalTracingMode(): TracingMode =
    localTracingMode.get()

  def setLocalTracingMode(mode: TracingMode): Unit =
    if (tracingEnabled) {
      localTracingMode.set(mode)
    }

  private def buildCachedFrame(lambdaRef: AnyRef): TraceFrame = {
    val cachedFr = frameCache.get(lambdaRef)
    if (cachedFr eq null) {
      val fr = buildFrame()
      frameCache.put(lambdaRef, fr)
      fr
    } else {
      cachedFr
    }
  }

  private def buildFrame(): TraceFrame = {
    // TODO: proper trace calculation
    val lines = new Throwable().getStackTrace.toList
      .map(TraceLine.fromStackTraceElement)
      .find(l => !classBlacklist.exists(b => l.className.startsWith(b)))
      .toList

    TraceFrame(lines)
  }

  /**
   * Cache for trace frames. Keys are references to:
   * - lambdas
   *
   * TODO: Consider thread-local or a regular, mutable map.
   * TODO: LRU entry-bounded cache.
   */
  private val frameCache: ConcurrentHashMap[AnyRef, TraceFrame] = new ConcurrentHashMap[AnyRef, TraceFrame]()

  private val localTracingMode: ThreadLocal[TracingMode] = new ThreadLocal[TracingMode] {
    override def initialValue(): TracingMode = TracingMode.Disabled
  }

  private val classBlacklist = List(
    "cats.effect.",
    "sbt.",
    "java.",
    "sun.",
    "scala."
  )

}
