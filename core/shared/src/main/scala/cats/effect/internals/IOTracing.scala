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
import cats.effect.tracing.TraceElement
import TracingPlatform.{tracingEnabled, traceCache}

private[effect] object IOTracing {

  // TODO: Lazily evaluate key?
  // calculating this key has a cost. inline the checks
  def check[A](source: IO[A], key: AnyRef): IO[A] = {
    if (tracingEnabled) {
      // The userspace method invocation is at least two frames away
      // TODO: filtering here?
      val cachedRef = traceCache.get(key)
      if (cachedRef eq null) {
        val stackTrace = new Throwable().getStackTrace.toList.map(TraceElement.fromStackTraceElement)
        traceCache.put(key, stackTrace)
        IO.Trace(source, stackTrace)
      } else {
        IO.Trace(source, cachedRef.asInstanceOf[List[TraceElement]])
      }
    } else {
      source
    }
  }

}
