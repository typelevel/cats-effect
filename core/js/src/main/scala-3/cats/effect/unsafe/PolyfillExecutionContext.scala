/*
 * Copyright 2020 Typelevel
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

package cats.effect.unsafe

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.Random

/**
 * Based on https://github.com/YuzuJS/setImmediate
 */
private[unsafe] object PolyfillExecutionContext extends ExecutionContext {
  private[this] val Undefined = "undefined"

  def execute(runnable: Runnable): Unit =
    setImmediate(() => runnable.run())

  def reportFailure(cause: Throwable): Unit =
    cause.printStackTrace()

  // typeof checks are broken on DottyJS (scala-js/scala-js#4194), so we use the fallback for now
  private[this] val setImmediate: (() => Unit) => Unit = { k =>
    js.Dynamic.global.setTimeout(k, 0)
    ()
  }
}
