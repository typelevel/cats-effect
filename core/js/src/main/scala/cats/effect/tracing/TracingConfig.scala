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

object TracingConfig {
  import TracingMode._

  private var _mode: TracingMode = Cached

  def mode: TracingMode = _mode
  def mode_=(mode: TracingMode): Unit = {
    _mode = mode
    mode match {
      case Off =>
        TracingConstants.isCachedStackTracing = false
        TracingConstants.isFullStackTracing = false
        TracingConstants.isStackTracing = false
      case Cached =>
        TracingConstants.isCachedStackTracing = true
        TracingConstants.isFullStackTracing = false
        TracingConstants.isStackTracing = true
      case Full =>
        TracingConstants.isCachedStackTracing = false
        TracingConstants.isFullStackTracing = true
        TracingConstants.isStackTracing = true
    }
  }

  sealed abstract class TracingMode
  object TracingMode {
    object Off extends TracingMode
    object Cached extends TracingMode
    object Full extends TracingMode
  }
}
