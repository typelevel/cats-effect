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

import scala.annotation.nowarn
import scala.scalanative.meta.LinktimeInfo

import java.util.concurrent.ConcurrentHashMap

private[tracing] abstract class TracingPlatform { self: Tracing.type =>

  import TracingConstants._

  private[this] val cache = new ConcurrentHashMap[Class[?], TracingEvent]

  def calculateTracingEvent(key: Any): TracingEvent =
    if (LinktimeInfo.debugMode) {
      if (isCachedStackTracing) {
        val cls = key.getClass
        val current = cache.get(cls)
        if (current eq null) {
          val event = buildEvent()
          cache.put(cls, event)
          event
        } else current
      } else if (isFullStackTracing) {
        buildEvent()
      } else {
        null
      }
    } else null

  @nowarn("msg=never used")
  private[tracing] def applyStackTraceFilter(
      callSiteClassName: String,
      callSiteMethodName: String,
      callSiteFileName: String): Boolean =
    isInternalClass(callSiteClassName)

  private[tracing] def decodeMethodName(name: String): String = name

}
