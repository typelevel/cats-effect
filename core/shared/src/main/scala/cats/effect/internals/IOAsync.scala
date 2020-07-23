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
import cats.effect.internals.TracingPlatform.{isCachedStackTracing, isFullStackTracing}

private[effect] object IOAsync {

  // Conveniennce function for internal Async calls that intend
  // to opt into tracing so the following code isn't repeated.
  def apply[A](k: (IOConnection, IOContext, Either[Throwable, A] => Unit) => Unit,
               trampolineAfter: Boolean = false,
               traceKey: AnyRef = null): IO[A] = {
    val trace = if (isCachedStackTracing) {
      IOTracing.cached(traceKey.getClass)
    } else if (isFullStackTracing) {
      IOTracing.uncached()
    } else {
      null
    }

    IO.Async((conn, ctx, cb) => k(conn, ctx, cb), trampolineAfter, trace)
  }

}
