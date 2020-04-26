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

import cats.effect.FiberRef

/**
 * Represents the state of execution for a
 * single fiber.
 */
final private[effect] class IOContext {

  // TODO: lazy?
  private val locals: ConcurrentHashMap[FiberRef[Any], Any] = new ConcurrentHashMap()

  def putLocal(key: FiberRef[Any], value: Any): Unit = {
    locals.put(key, value)
    ()
  }

  def getLocal(key: FiberRef[Any]): Option[Any] =
    Option(locals.get(key))

}

object IOContext {

  def apply(): IOContext =
    new IOContext

}
