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

import scala.annotation.nowarn
import scala.reflect.NameTransformer

private[tracing] abstract class TracingPlatform extends ClassValue[TracingEvent] {
  self: Tracing.type =>

  import TracingConstants._

  override protected def computeValue(cls: Class[_]): TracingEvent = {
    buildEvent()
  }

  def calculateTracingEvent(key: Any): TracingEvent = {
    val cls = key.getClass
    if (isCachedStackTracing) {
      get(cls)
    } else if (isFullStackTracing) {
      buildEvent()
    } else {
      null
    }
  }

  private[this] final val stackTraceFilter: Array[String] = Array(
    "cats.effect.",
    "cats.",
    "sbt.",
    "java.",
    "sun.",
    "scala."
  )

  @nowarn("cat=unused")
  private[tracing] def applyStackTraceFilter(
      callSiteClassName: String,
      callSiteMethodName: String,
      callSiteFileName: String): Boolean = {
    val len = stackTraceFilter.length
    var idx = 0
    while (idx < len) {
      if (callSiteClassName.startsWith(stackTraceFilter(idx))) {
        return true
      }

      idx += 1
    }

    false
  }

  private[tracing] def decodeMethodName(name: String): String =
    NameTransformer.decode(name)

}
