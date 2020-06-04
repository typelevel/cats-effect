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
import cats.effect.IO.Trace
import cats.effect.tracing.{IOTrace, TraceFrame, TraceTag, TracingMode}

private[effect] object IOTracing {

  // TODO: It may be worth tracking mode in the global flag
  // marking the ones we want. This avoids a thread-local on uncachable
  // nodes which incurs the bulk of the performance hit.
  def uncached[A](source: IO[A], traceTag: TraceTag): IO[A] =
    localTracingMode.get() match {
      case TracingMode.Slug => Trace(source, buildFrame(traceTag))
      case _ => source
    }

  // TODO: Avoid trace tag for primitive ops and rely on class
  def cached[A](source: IO[A], traceTag: TraceTag, clazz: Class[_]): IO[A] =
    localTracingMode.get() match {
      case TracingMode.Rabbit => Trace(source, buildCachedFrame(traceTag, clazz))
      case TracingMode.Slug => Trace(source, buildFrame(traceTag))
      case TracingMode.Disabled => source
    }

  def trace(traceTag: TraceTag, clazz: Class[_]): TraceFrame =
    buildCachedFrame(traceTag, clazz)

  def locallyTraced[A](source: IO[A], newMode: TracingMode): IO[A] =
    for {
      _ <- resetTrace
      a <- IO.suspend {
        val oldMode = localTracingMode.get()
        localTracingMode.set(newMode)

        // In the event of cancellation, the tracing mode will be reset
        // when the thread grabs a new task to run (via Async or IORunLoop.start).
        source.redeemWith(
          e => IO.suspend {
            localTracingMode.set(oldMode)
            IO.raiseError(e)
          },
          a => IO.suspend {
            localTracingMode.set(oldMode)
            IO.pure(a)
          }
        )
      }
    } yield a

  def getLocalTracingMode(): TracingMode =
    localTracingMode.get()

  def setLocalTracingMode(mode: TracingMode): Unit =
    localTracingMode.set(mode)

  // TODO: def might be faster than value
  val backtrace: IO[IOTrace] =
    IO.Async { (_, ctx, cb) =>
      cb(Right(ctx.getTrace))
    }

  private def buildCachedFrame(traceTag: TraceTag, keyClass: Class[_]): TraceFrame = {
    val cachedFr = frameCache.get(keyClass)
    if (cachedFr eq null) {
      val fr = buildFrame(traceTag)
      frameCache.put(keyClass, fr)
      fr
    } else {
      cachedFr
    }
  }

  def buildFrame(traceTag: TraceTag): TraceFrame = {
    // TODO: proper trace calculation
    val stackTrace = new Throwable().getStackTrace.toList
      .dropWhile(l => classBlacklist.exists(b => l.getClassName.startsWith(b)))

    TraceFrame(traceTag, stackTrace)
  }

  private[this] val resetTrace: IO[Unit] =
    IO.Async { (_, ctx, cb) =>
      ctx.resetTrace()
      cb(Right(()))
    }

  /**
   * Cache for trace frames. Keys are references to:
   * - lambda classes
   */
  private[this] val frameCache: ConcurrentHashMap[Class[_], TraceFrame] = new ConcurrentHashMap()

  /**
   * Thread-local state that stores the lexical tracing
   * mode for the fiber bound to the current thread.
   */
  private[this] val localTracingMode: ThreadLocal[TracingMode] = ThreadLocal.withInitial(() => TracingMode.Disabled)

  private[this] val classBlacklist = List(
    "cats.effect.",
    "cats.",
    "sbt.",
    "java.",
    "sun.",
    "scala."
  )

}
