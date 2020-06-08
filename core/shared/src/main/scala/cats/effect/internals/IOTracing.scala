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

import cats.implicits._
import cats.effect.IO
import cats.effect.IO.{CollectTraces, Pure, RaiseError, Trace}
import cats.effect.tracing.{TraceFrame, TraceTag}

private[effect] object IOTracing {

  def uncached[A](source: IO[A], traceTag: TraceTag): IO[A] = {
//    localTracingMode.get() match {
//      case TracingMode.Slug => Trace(source, buildFrame(traceTag))
//      case _ => source
//    }
    Trace(source, buildFrame(traceTag))
  }

  // TODO: Avoid trace tag for primitive ops and rely on class
  def cached[A](source: IO[A], traceTag: TraceTag, clazz: Class[_]): IO[A] = {
//    localTracingMode.get() match {
//      case TracingMode.Rabbit => Trace(source, buildCachedFrame(traceTag, clazz))
//      case TracingMode.Slug => Trace(source, buildFrame(traceTag))
//      case TracingMode.Disabled => source
//    }
    println(clazz)
    Trace(source, buildFrame(traceTag))
  }

  def trace(traceTag: TraceTag, clazz: Class[_]): TraceFrame =
    buildCachedFrame(traceTag, clazz)

  def traced[A](source: IO[A]): IO[A] =
    resetTrace *> enableCollection *> source.flatMap(DisableCollection.asInstanceOf[A => IO[A]])

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

  private[this] val enableCollection: IO[Unit] = CollectTraces(true)

  private[this] val disableCollection: IO[Unit] = CollectTraces(false)

  private object DisableCollection extends IOFrame[Any, IO[Any]] {
    override def apply(a: Any) =
      disableCollection *> Pure(a)
    override def recover(e: Throwable) =
      disableCollection *> RaiseError(e)
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
