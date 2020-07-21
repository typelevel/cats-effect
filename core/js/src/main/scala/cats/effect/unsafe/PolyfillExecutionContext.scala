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

  private[this] val setImmediate: (() => Unit) => Unit = {
    if (js.typeOf(js.Dynamic.global.setImmediate) == Undefined) {
      var nextHandle = 1
      val tasksByHandle = mutable.Map[Int, () => Unit]()
      var currentlyRunningATask = false

      def canUsePostMessage(): Boolean = {
        // The test against `importScripts` prevents this implementation from being installed inside a web worker,
        // where `global.postMessage` means something completely different and can't be used for this purpose.
        if (js.typeOf(js.Dynamic.global.postMessage) != Undefined && js.typeOf(
            js.Dynamic.global.importScripts) == Undefined) {
          var postMessageIsAsynchronous = true
          val oldOnMessage = js.Dynamic.global.onmessage

          js.Dynamic.global.onmessage = { () => postMessageIsAsynchronous = false }

          js.Dynamic.global.postMessage("", "*")
          js.Dynamic.global.onmessage = oldOnMessage
          postMessageIsAsynchronous
        } else {
          false
        }
      }

      def runIfPresent(handle: Int): Unit = {
        if (currentlyRunningATask) {
          js.Dynamic.global.setTimeout(() => runIfPresent(handle), 0)
        } else {
          tasksByHandle.get(handle) match {
            case Some(task) =>
              currentlyRunningATask = true
              try {
                task()
              } finally {
                tasksByHandle -= handle
                currentlyRunningATask = false
              }

            case None =>
          }
        }

        ()
      }

      if (canUsePostMessage()) {
        // postMessage is what we use for most modern browsers (when not in a webworker)

        // generate a unique messagePrefix for everything we do
        // collision here is *extremely* unlikely, but the random makes it somewhat less so
        // as an example, if end-user code is using the setImmediate.js polyfill, we don't
        // want to accidentally collide. Then again, if they *are* using the polyfill, we
        // would pick it up above unless they init us first. Either way, the odds of
        // collision here are microscopic.
        val messagePrefix = "setImmediate$" + Random.nextInt() + "$"

        def onGlobalMessage(event: js.Dynamic): Unit = {
          if (/*event.source == js.Dynamic.global.global &&*/ js.typeOf(
              event.data) == "string" && event
              .data
              .indexOf(messagePrefix)
              .asInstanceOf[Int] == 0) {
            runIfPresent(event.data.toString.substring(messagePrefix.length).toInt)
          }
        }

        if (js.typeOf(js.Dynamic.global.addEventListener) != Undefined) {
          js.Dynamic.global.addEventListener("message", onGlobalMessage _, false)
        } else {
          js.Dynamic.global.attachEvent("onmessage", onGlobalMessage _)
        }

        { k =>
          val handle = nextHandle
          nextHandle += 1

          tasksByHandle += (handle -> k)
          js.Dynamic.global.postMessage(messagePrefix + handle, "*")
          ()
        }
      } else {
        // we don't try to look for process.nextTick since scalajs doesn't support old node
        // we're also not going to bother fast-pathing for IE6; just fall through

        { k =>
          js.Dynamic.global.setTimeout(k, 0)
          ()
        }
      }
    } else {
      { k =>
        js.Dynamic.global.setImmediate(k)
        ()
      }
    }
  }
}
