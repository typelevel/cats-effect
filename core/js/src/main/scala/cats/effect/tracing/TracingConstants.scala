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

import scala.scalajs.js

private[effect] object TracingConstants {
  private[this] final val stackTracingMode: String =
    try {
      if (js.typeOf(js.Dynamic.global.process) != "undefined" &&
        js.typeOf(js.Dynamic.global.process.env) != "undefined") {
        val env = js.Dynamic.global.process.env
        if (js.typeOf(env.selectDynamic("CATS_EFFECT_TRACING_MODE")) != "undefined") {
          env.CATS_EFFECT_TRACING_MODE.toString
        } else ""
      } else ""
    } catch {
      case _: Throwable => ""
    }

  final val isCachedStackTracing: Boolean = stackTracingMode.equalsIgnoreCase("cached")

  final val isFullStackTracing: Boolean = stackTracingMode.equalsIgnoreCase("full")

  final val isStackTracing: Boolean = isFullStackTracing || isCachedStackTracing

  final val WASM_IDENTICAL_FUNCTION: AnyRef = {
    val fn = () => ()
    fn.asInstanceOf[js.Dynamic].wasmIdentical = true
    fn.asInstanceOf[AnyRef]
  }

  final val WASM_IDENTICAL_EVENT: TracingEvent =
    TracingEvent.WasmTrace(Array.empty, isIdentical = true)

  def isWasmIdenticalFunction(f: AnyRef): Boolean = {
    js.typeOf(f.asInstanceOf[js.Dynamic].wasmIdentical) == "boolean" &&
    f.asInstanceOf[js.Dynamic].wasmIdentical.asInstanceOf[Boolean]
  }
}
