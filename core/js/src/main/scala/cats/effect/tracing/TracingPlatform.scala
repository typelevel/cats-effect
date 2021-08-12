/*
 * Copyright 2020-2021 Typelevel
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

package cats.effect.tracing

import cats.effect.kernel.Cont

import scala.reflect.NameTransformer
import scala.scalajs.js

private[tracing] abstract class TracingPlatform { self: Tracing.type =>

  private val cache = js.Dictionary.empty[TracingEvent]
  private val function0Property = js.Object.getOwnPropertyNames((() => ()).asInstanceOf[js.Object])(0)
  private val function1Property = js.Object.getOwnPropertyNames(((_: Unit) => ()).asInstanceOf[js.Object])(0)

  import TracingConstants._

  def calculateTracingEvent[A](f: Function0[A]): TracingEvent = {
    calculateTracingEvent(f.asInstanceOf[js.Dictionary[js.Any]](function0Property).toString())
  }

  def calculateTracingEvent[A, B](f: Function1[A, B]): TracingEvent = {
    calculateTracingEvent(f.asInstanceOf[js.Dictionary[js.Any]](function1Property).toString())
  }

  // We could have a catch-all for non-functions, but explicitly enumerating makes sure we handle each case correctly
  def calculateTracingEvent[F[_], A, B](cont: Cont[F, A, B]): TracingEvent = {
    calculateTracingEvent(cont.getClass().getName())
  }

  private def calculateTracingEvent(key: String): TracingEvent = {
    if (isCachedStackTracing)
      cache.getOrElseUpdate(key, buildEvent())
    else if (isFullStackTracing)
      buildEvent()
    else
      null
  }

  private[this] final val stackTraceClassNameFilter: Array[String] = Array(
    "cats.effect.",
    "cats.",
    "java.",
    "scala."
  )

  private[this] final val stackTraceMethodNameFilter: Array[String] = Array(
    "_jl_",
    "_Lcats_effect_"
  )

  private[tracing] def applyStackTraceFilter(
      callSiteClassName: String,
      callSiteMethodName: String): Boolean = {
    if (callSiteClassName == "<jscode>") {
      val len = stackTraceMethodNameFilter.length
      var idx = 0
      while (idx < len) {
        if (callSiteMethodName.contains(stackTraceMethodNameFilter(idx))) {
          return true
        }

        idx += 1
      }
    } else {
      val len = stackTraceClassNameFilter.length
      var idx = 0
      while (idx < len) {
        if (callSiteClassName.startsWith(stackTraceClassNameFilter(idx))) {
          return true
        }

        idx += 1
      }
    }

    false
  }

  private[tracing] def decodeMethodName(name: String): String = {
    val junk = name.indexOf("__")
    NameTransformer.decode(if (junk == -1) name else name.substring(0, junk))
  }

}
