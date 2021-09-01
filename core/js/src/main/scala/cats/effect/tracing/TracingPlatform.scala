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

import scala.collection.mutable
import scala.reflect.NameTransformer
import scala.scalajs.js

private[tracing] abstract class TracingPlatform { self: Tracing.type =>

  private[this] val cache = mutable.Map[Any, TracingEvent]()
  private[this] val function0Property =
    js.Object.getOwnPropertyNames((() => ()).asInstanceOf[js.Object])(0)
  private[this] val function1Property =
    js.Object.getOwnPropertyNames(((_: Unit) => ()).asInstanceOf[js.Object])(0)

  import TracingConstants._

  def calculateTracingEvent[A](f: Function0[A]): TracingEvent = {
    calculateTracingEvent(
      f.asInstanceOf[js.Dynamic].selectDynamic(function0Property).toString())
  }

  def calculateTracingEvent[A, B](f: Function1[A, B]): TracingEvent = {
    calculateTracingEvent(
      f.asInstanceOf[js.Dynamic].selectDynamic(function1Property).toString())
  }

  // We could have a catch-all for non-functions, but explicitly enumerating makes sure we handle each case correctly
  def calculateTracingEvent[F[_], A, B](cont: Cont[F, A, B]): TracingEvent = {
    calculateTracingEvent(cont.getClass())
  }

  private[this] def calculateTracingEvent(key: Any): TracingEvent = {
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
    "_Lcats_effect_",
    "_jl_"
  )

  private[this] final val stackTraceFileNameFilter: Array[String] = Array(
    "githubusercontent.com/typelevel/cats-effect/",
    "githubusercontent.com/typelevel/cats/",
    "githubusercontent.com/scala-js/",
    "githubusercontent.com/scala/"
  )

  private[tracing] def applyStackTraceFilter(
      callSiteClassName: String,
      callSiteMethodName: String,
      callSiteFileName: String): Boolean = {
    if (callSiteClassName == "<jscode>") {
      {
        val len = stackTraceMethodNameFilter.length
        var idx = 0
        while (idx < len) {
          if (callSiteMethodName.contains(stackTraceMethodNameFilter(idx))) {
            return true
          }

          idx += 1
        }
      }

      {
        val len = stackTraceFileNameFilter.length
        var idx = 0
        while (idx < len) {
          if (callSiteFileName.contains(stackTraceFileNameFilter(idx))) {
            return true
          }

          idx += 1
        }
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
