/*
 * Copyright 2020-2025 Typelevel
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
  import TracingConstants._

  private[this] val cache = mutable.Map.empty[Any, TracingEvent].withDefaultValue(null)

  private[this] lazy val function0Property: String = {
    if (isWasm) ""
    else {
      try {
        js.Object.getOwnPropertyNames((() => ()).asInstanceOf[js.Object])(0)
      } catch {
        case _: Throwable => ""
      }
    }
  }

  private[this] lazy val function1Property: String = {
    if (isWasm) ""
    else {
      try {
        js.Object.getOwnPropertyNames(((_: Unit) => ()).asInstanceOf[js.Object])(0)
      } catch {
        case _: Throwable => ""
      }
    }
  }

  private[this] lazy val isWasm: Boolean =
    System.getenv("WASM_MODE") == "true" ||
      js.typeOf(js.Dynamic.global.WebAssembly) != "undefined"

  private[this] def buildWasmEvent(isIdentical: Boolean = false): TracingEvent = {
    TracingEvent.WasmTrace(Array.empty, isIdentical)
  }

  def calculateTracingEvent[A](f: Function0[A]): TracingEvent = {
    if (isWasm) {
      WASM_IDENTICAL_EVENT
    } else {
      try {
        calculateTracingEvent(
          f.asInstanceOf[js.Dynamic].selectDynamic(function0Property).toString())
      } catch {
        case _: Throwable => null
      }
    }
  }

  def calculateTracingEvent[A, B](f: Function1[A, B]): TracingEvent = {
    if (isWasm) {
      if (isWasmIdenticalFunction(f.asInstanceOf[AnyRef])) WASM_IDENTICAL_EVENT
      else buildWasmEvent()
    } else {
      try {
        calculateTracingEvent(
          f.asInstanceOf[js.Dynamic].selectDynamic(function1Property).toString())
      } catch {
        case _: Throwable => null
      }
    }
  }

  def calculateTracingEvent[F[_], A, B](cont: Cont[F, A, B]): TracingEvent = {
    if (isWasm) buildWasmEvent()
    else {
      try {
        calculateTracingEvent(cont.getClass())
      } catch {
        case _: Throwable => null
      }
    }
  }

  private[this] final val calculateTracingEvent: Any => TracingEvent = {
    if (isWasm) _ => buildWasmEvent()
    else if (LinkingInfo.developmentMode) {
      if (isCachedStackTracing) { key =>
        try {
          val current = cache(key)
          if (current eq null) {
            val event = buildEvent()
            cache(key) = event
            event
          } else current
        } catch {
          case _: Throwable => null
        }
      } else if (isFullStackTracing)
        _ => buildEvent()
      else
        _ => null
    } else { _ => null }
  }

  private[this] final val stackTraceFileNameFilter: Array[String] = Array(
    "githubusercontent.com/typelevel/cats-effect/",
    "githubusercontent.com/typelevel/cats/",
    "githubusercontent.com/scala-js/",
    "githubusercontent.com/scala/"
  )

  private[this] def isInternalFile(fileName: String): Boolean = {
    if (fileName == null) false
    else {
      var i = 0
      val len = stackTraceFileNameFilter.length
      while (i < len) {
        if (fileName.contains(stackTraceFileNameFilter(i)))
          return true
        i += 1
      }
      false
    }
  }

  private[this] final val stackTraceMethodNameFilter: Array[String] = Array(
    "_Lcats_effect_",
    "_jl_",
    "_Lorg_scalajs_"
  )

  private[this] def isInternalMethod(methodName: String): Boolean = {
    if (methodName == null) false
    else {
      var i = 0
      val len = stackTraceMethodNameFilter.length
      while (i < len) {
        if (methodName.contains(stackTraceMethodNameFilter(i)))
          return true
        i += 1
      }
      false
    }
  }

  private[tracing] def applyStackTraceFilter(
      callSiteClassName: String,
      callSiteMethodName: String,
      callSiteFileName: String): Boolean = {

    def isInternalScalaFile =
      (callSiteFileName ne null) && !callSiteFileName.endsWith(".js") && isInternalFile(
        callSiteFileName)

    def isInternalJSCode = callSiteClassName == "<jscode>" &&
      (isInternalScalaFile || isInternalMethod(callSiteMethodName))

    isInternalJSCode || isInternalClass(callSiteClassName)
  }

  private[tracing] def decodeMethodName(name: String): String = {
    if (name == null) ""
    else {
      val junk = name.indexOf("__")
      NameTransformer.decode(if (junk == -1) name else name.substring(0, junk))
    }
  }
}
