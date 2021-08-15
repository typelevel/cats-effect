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

import scala.scalajs.js

private object TracingConstants {

  @inline private[this] def definedOrNone[A <: js.Any](x: => A): Option[A] =
    if (js.typeOf(x) != "undefined")
      Option(x) // Option constructor checks for null
    else
      None

  @inline def stackTracingMode: String =
    definedOrNone(js.Dynamic.global.process)
      .flatMap(p => definedOrNone(p.env))
      .flatMap { env =>
        definedOrNone(env.CATS_EFFECT_TRACING_MODE).orElse(
          definedOrNone(env.REACT_APP_CATS_EFFECT_TRACING_MODE))
      }
      .map(_.asInstanceOf[String])
      .filterNot(_.isEmpty)
      .getOrElse("cached")

  @inline def isCachedStackTracing: Boolean = stackTracingMode.equalsIgnoreCase("cached")

  @inline def isFullStackTracing: Boolean = stackTracingMode.equalsIgnoreCase("full")

  @inline def isStackTracing: Boolean = isFullStackTracing || isCachedStackTracing

}
