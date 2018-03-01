/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

import cats.effect.util.CompositeException
import scala.collection.mutable.ListBuffer

/**
 * INTERNAL API - utilities for dealing with cancelable thunks.
 */
private[effect] object Cancelable {
  type Type = () => Unit

  /** Reusable, no-op cancelable reference. */
  val dummy: Type = new Dummy

  /** For building reusable, no-op cancelable references. */
  class Dummy extends Type {
    def apply(): Unit = ()
  }

  /**
   * Given a list of cancelables, cancels all, delaying all
   * exceptions until all references are canceled.
   */
  def cancelAll(cancelables: Type*): Unit = {
    val errors = ListBuffer.empty[Throwable]
    val cursor = cancelables.iterator

    while (cursor.hasNext) {
      try cursor.next().apply()
      catch { case e if NonFatal(e) => errors += e }
    }

    errors.toList match {
      case Nil => ()
      case x :: Nil => throw x
      case first :: second :: rest =>
        throw CompositeException(first, second, rest)
    }
  }
}
