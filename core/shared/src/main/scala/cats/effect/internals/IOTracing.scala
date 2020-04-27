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
import cats.effect.tracing.{TraceFrame, TraceLine, TracingMode}
import cats.effect.internals.TracingPlatform.tracingEnabled
import cats.effect.internals.TracingFlagsPlatform.tracingMode

import scala.collection.mutable

private[effect] object IOTracing {

  def apply[A](source: IO[A], lambdaRef: AnyRef): IO[A] = {
    // TODO: consider inlining this conditional at call-sites
    if (tracingEnabled) {
      val frame = tracingMode match {
        case TracingMode.Rabbit => buildCachedFrame(lambdaRef)
        case TracingMode.Slug => buildFrame()
      }
      IO.Trace(source, frame)
    } else {
      source
    }
  }

  private def buildCachedFrame(lambdaRef: AnyRef): TraceFrame =
    frameCache.get(lambdaRef) match {
      case Some(fr) => fr
      case None =>
        val fr = buildFrame()
        frameCache.put(lambdaRef, fr)
        fr
    }

  private def buildFrame(): TraceFrame = {
    // TODO: proper trace calculation
    val lines = new Throwable()
      .getStackTrace
      .toList
      .map(TraceLine.fromStackTraceElement)
//      .filter(_.className.startsWith("cats.effect.internals.Main"))

    TraceFrame(lines)
  }

  /**
   * Cache for trace frames. Keys are object references for lambdas.
   *
   * TODO: Switch to thread-local or j.u.chm?
   * TODO: Bound the cache.
   */
  private val frameCache: mutable.Map[AnyRef, TraceFrame] = new mutable.HashMap()

}
