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

// import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.scalajs.js

/**
 * Based on https://github.com/YuzuJS/setImmediate
 */
private[unsafe] object PolyfillExecutionContext extends ExecutionContext {
  private[this] val Undefined = "undefined"

  def execute(runnable: Runnable): Unit =
    setImmediate(() => runnable.run())

  def reportFailure(cause: Throwable): Unit =
    cause.printStackTrace()

  private[this] val setImmediate: (() => Unit) => Unit = {
    if (js.typeOf(js.Dynamic.global.setImmediate) == Undefined) {
      /*var nextHandle = 1
      val tasksByHandle = mutable.Map[Int, () => Unit]()
      var currentlyRunningATask = false
      val doc = js.Dynamic.global.document*/

      /*if (js.eval("{}.toString.call(global.process)") == "[object process]") {
        // Node.js before 0.9
        js.Dynamic.process.nextTick(function () { runIfPresent(handle); })
      } else {

      }*/
      { k =>
        js.Dynamic.global.setTimeout(k, 0)
        ()
      }
    } else {
      { k =>
        js.Dynamic.global.setImmediate(k)
        ()
      }
    }
  }
}
