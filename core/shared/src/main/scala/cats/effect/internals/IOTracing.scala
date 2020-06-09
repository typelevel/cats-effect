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
import cats.effect.IO.{CollectTraces, Pure, RaiseError, Trace}
import cats.effect.tracing.{TraceFrame, TraceTag}

private[effect] object IOTracing {

  def decorated[A](source: IO[A], traceTag: TraceTag): IO[A] =
    Trace(source, buildFrame(traceTag))

  // TODO: Avoid trace tag for primitive ops and rely on class
  def uncached(traceTag: TraceTag): TraceFrame =
    buildFrame(traceTag)

  def cached(traceTag: TraceTag, clazz: Class[_]): TraceFrame =
    buildCachedFrame(traceTag, clazz)

  def traced[A](source: IO[A]): IO[A] =
    resetTrace *> incrementCollection *> source.flatMap(DecrementTraceCollection.asInstanceOf[A => IO[A]])

  private def buildCachedFrame(traceTag: TraceTag, clazz: Class[_]): TraceFrame = {
    val cachedFr = frameCache.get(clazz)
    if (cachedFr eq null) {
      val fr = buildFrame(traceTag)
      frameCache.put(clazz, fr)
      fr
    } else {
      cachedFr
    }
  }

  def buildFrame(traceTag: TraceTag): TraceFrame = {
    val stackTrace = new Throwable().getStackTrace.toList
      .dropWhile(l => classBlacklist.exists(b => l.getClassName.startsWith(b)))

    TraceFrame(traceTag, stackTrace)
  }

  private[this] val incrementCollection: IO[Unit] = CollectTraces(true)

  private[this] val decrementCollection: IO[Unit] = CollectTraces(false)

  private object DecrementTraceCollection extends IOFrame[Any, IO[Any]] {
    override def apply(a: Any) =
      decrementCollection *> Pure(a)
    override def recover(e: Throwable) =
      decrementCollection *> RaiseError(e)
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

  private[this] val classBlacklist = List(
    "cats.effect.",
    "cats.",
    "sbt.",
    "java.",
    "sun.",
    "scala."
  )

}
