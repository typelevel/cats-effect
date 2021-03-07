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

package cats.effect

import java.util.concurrent.ConcurrentHashMap

private[effect] object IOTracing {

  /**
   * Global cache for trace frames. Keys are references to lambda classes.
   * Should converge to the working set of traces very quickly for hot code paths.
   */
  private[this] val frameCache: ConcurrentHashMap[Class[_], IOEvent] = new ConcurrentHashMap()

  def calculateStackTraceEvent(clazz: Class[_]): IOEvent =
    if (TracingConstants.isCachedStackTracing) {
      val currentFrame = frameCache.get(clazz)
      if (currentFrame eq null) {
        val newFrame = buildFrame()
        frameCache.put(clazz, newFrame)
        newFrame
      } else {
        currentFrame
      }
    } else if (TracingConstants.isFullStackTracing) {
      buildFrame()
    } else {
      null
    }

  private def buildFrame(): IOEvent =
    IOEvent.StackTrace(new Throwable().getStackTrace.toList)

}
