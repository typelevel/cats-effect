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

import cats.effect.IO
import cats.effect.tracing.{IOTrace, TraceLine}
import cats.effect.internals.TracingPlatform.tracingEnabled

import scala.collection.mutable

private[effect] object IOTracing {

  // TODO: Lazily evaluate key?
  // calculating this key has a cost. inline the checks
  def apply[A](source: IO[A], lambda: AnyRef): IO[A] = {
    if (tracingEnabled) {
      val cachedTrace = traceCache.get(lambda)
      cachedTrace match {
        case Some(trace) =>
          IO.Trace(source, trace)
        case None => {
          val fiberTrace = buildTrace()
          traceCache.put(lambda, fiberTrace)
          IO.Trace(source, fiberTrace)
        }
      }
    } else {
      source
    }
  }

  private def buildTrace(): IOTrace = {
    // TODO: proper trace calculation
    val lines = new Throwable()
      .getStackTrace
      .toList
      .map(TraceLine.fromStackTraceElement)
      .filter(_.className.startsWith("cats.effect.internals.Main"))

    IOTrace(lines)
  }

  /**
   * Global, thread-safe cache for traces. Keys are generally
   * lambda references.
   *
   * TODO: Could this be a thread-local?
   * If every thread eventually calculates its own set,
   * there should be no issue?
   *
   * TODO: Bound the cache.
   */
  private val traceCache: mutable.Map[AnyRef, IOTrace] = new mutable.HashMap()

}
