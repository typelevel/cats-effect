/*
 * Copyright 2020-2024 Typelevel
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
import scala.scalajs.{js, LinkingInfo}

private[tracing] abstract class TracingPlatform { self: Tracing.type =>

  private[this] val cache = mutable.Map.empty[Any, TracingEvent].withDefaultValue(null)
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

  private[this] final val calculateTracingEvent: Any => TracingEvent = {
    if (LinkingInfo.developmentMode) {
      if (isCachedStackTracing) { key =>
        val current = cache(key)
        if (current eq null) {
          val event = buildEvent()
          cache(key) = event
          event
        } else current
      } else if (isFullStackTracing)
        _ => buildEvent()
      else
        _ => null
    } else
      _ => null
  }

  // These filters require properly-configured source maps
  private[this] final val stackTraceFileNameFilter: Array[String] = Array(
    "githubusercontent.com/typelevel/cats-effect/",
    "githubusercontent.com/typelevel/cats/",
    "githubusercontent.com/scala-js/",
    "githubusercontent.com/scala/"
  )

  private[this] def isInternalFile(fileName: String): Boolean = {
    var i = 0
    val len = stackTraceFileNameFilter.length
    while (i < len) {
      if (fileName.contains(stackTraceFileNameFilter(i)))
        return true
      i += 1
    }
    false
  }

  // These filters target Firefox
  private[this] final val stackTraceMethodNameFilter: Array[String] = Array(
    "_Lcats_effect_",
    "_jl_",
    "_Lorg_scalajs_"
  )

  private[this] def isInternalMethod(methodName: String): Boolean = {
    var i = 0
    val len = stackTraceMethodNameFilter.length
    while (i < len) {
      if (methodName.contains(stackTraceMethodNameFilter(i)))
        return true
      i += 1
    }
    false
  }

  private[tracing] def applyStackTraceFilter(
      callSiteClassName: String,
      callSiteMethodName: String,
      callSiteFileName: String): Boolean = {

    // anonymous lambdas can only be distinguished by Scala source-location, if available
    def isInternalScalaFile =
      (callSiteFileName ne null) && !callSiteFileName.endsWith(".js") && isInternalFile(
        callSiteFileName)

    // this is either a lambda or we are in Firefox
    def isInternalJSCode = callSiteClassName == "<jscode>" &&
      (isInternalScalaFile || isInternalMethod(callSiteMethodName))

    isInternalJSCode || isInternalClass(callSiteClassName) // V8 class names behave like Java
  }

  private[tracing] def decodeMethodName(name: String): String = {
    val junk = name.indexOf("__") // Firefox artifacts
    NameTransformer.decode(if (junk == -1) name else name.substring(0, junk))
  }

}
