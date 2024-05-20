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

package cats.effect
package tracing

import scala.scalajs.js

private[effect] object TracingConstants {

  private[this] final val stackTracingMode: String =
    process.env("CATS_EFFECT_TRACING_MODE").filterNot(_.isEmpty).getOrElse {
      if (js.typeOf(js.Dynamic.global.process) != "undefined"
        && js.typeOf(js.Dynamic.global.process.release) != "undefined"
        && js.Dynamic.global.process.release.name == "node".asInstanceOf[js.Any])
        "cached"
      else
        "none"
    }

  final val isCachedStackTracing: Boolean = stackTracingMode.equalsIgnoreCase("cached")

  final val isFullStackTracing: Boolean = stackTracingMode.equalsIgnoreCase("full")

  final val isStackTracing: Boolean = isFullStackTracing || isCachedStackTracing
}
