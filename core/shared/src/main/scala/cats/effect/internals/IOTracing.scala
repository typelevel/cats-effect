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
import cats.effect.tracing.StackTraceFrame

private[effect] object IOTracing {

  def decorated[A](source: IO[A], tag: Int): IO[A] =
    Trace(source, buildFrame(tag))

  def uncached(tag: Int): StackTraceFrame =
    buildFrame(tag)

  def cached(tag: Int, clazz: Class[_]): StackTraceFrame =
    buildCachedFrame(tag, clazz)

  private def buildCachedFrame(tag: Int, clazz: Class[_]): StackTraceFrame = {
    val cf = frameCache.get(clazz)
    if (cf eq null) {
      val f = buildFrame(tag)
      frameCache.put(clazz, f)
      f
    } else {
      cf
    }
  }

  def buildFrame(tag: Int): StackTraceFrame =
    StackTraceFrame(tag, new Throwable())

  /**
   * Cache for trace frames. Keys are references to lambda classes.
   */
  private[this] val frameCache: ConcurrentHashMap[Class[_], StackTraceFrame] = new ConcurrentHashMap()

}
