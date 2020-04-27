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

import cats.effect.tracing.IOTrace

final private[effect] class IOContext private () {

  // This is declared volatile but it is accessed
  // from at most one thread at a time.
  @volatile var trace: IOTrace = IOTrace.Empty

  def pushTrace(that: IOTrace): Unit = {
    trace = trace.push(that)
  }

}

object IOContext {
  def newContext: IOContext =
    new IOContext
}
