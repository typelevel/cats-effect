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
import cats.effect.tracing.{IOTrace, TraceElement}
import TracingPlatform.{tracingEnabled, traceCache}

private[effect] object IOTracing {

  // TODO: Lazily evaluate key?
  // calculating this key has a cost. inline the checks
  def apply[A](source: IO[A], lambda: AnyRef): IO[A] = {
    if (tracingEnabled) {
      val traceRef = traceCache.get(lambda)
      if (traceRef eq null) {
        val fiberTrace = createTrace()
        traceCache.put(lambda, fiberTrace)
        IO.Trace(source, fiberTrace)
      } else {
        IO.Trace(source, traceRef.asInstanceOf[IOTrace])
      }
    } else {
      source
    }
  }

  def createTrace(): IOTrace = {
    // TODO: calculate trace here
    val lines = new Throwable().getStackTrace.toList.map(TraceElement.fromStackTraceElement)
    IOTrace(lines)
  }

}
