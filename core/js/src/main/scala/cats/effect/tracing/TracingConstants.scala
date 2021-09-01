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
import scala.util.Try

private object TracingConstants {

  private[this] val stackTracingMode: String =
    Try(js.Dynamic.global.process.env.CATS_EFFECT_TRACING_MODE)
      .toOption
      .orElse(Try(js.Dynamic.global.process.env.REACT_APP_CATS_EFFECT_TRACING_MODE).toOption)
      .filterNot(js.isUndefined)
      .map(_.asInstanceOf[String])
      .filterNot(_.isEmpty)
      .getOrElse("cached")

  val isCachedStackTracing: Boolean = stackTracingMode.equalsIgnoreCase("cached")

  val isFullStackTracing: Boolean = stackTracingMode.equalsIgnoreCase("full")

  val isStackTracing: Boolean = isFullStackTracing || isCachedStackTracing

}
