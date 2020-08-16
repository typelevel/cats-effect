/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals

import java.util.concurrent.ConcurrentHashMap

import cats.effect.IO
import cats.effect.IO.Trace
import cats.effect.tracing.IOEvent

private[effect] object IOTracing {

  def decorated[A](source: IO[A]): IO[A] =
    Trace(source, buildFrame())

  def uncached(): IOEvent =
    buildFrame()

  def cached(clazz: Class[_]): IOEvent =
    buildCachedFrame(clazz)

  private def buildCachedFrame(clazz: Class[_]): IOEvent = {
    val currentFrame = frameCache.get(clazz)
    if (currentFrame eq null) {
      val newFrame = buildFrame()
      frameCache.put(clazz, newFrame)
      newFrame
    } else {
      currentFrame
    }
  }

  private def buildFrame(): IOEvent =
    IOEvent.StackTrace(new Throwable().getStackTrace.toList)

  /**
   * Global cache for trace frames. Keys are references to lambda classes.
   * Should converge to the working set of traces very quickly for hot code paths.
   */
  private[this] val frameCache: ConcurrentHashMap[Class[_], IOEvent] = new ConcurrentHashMap()

}
