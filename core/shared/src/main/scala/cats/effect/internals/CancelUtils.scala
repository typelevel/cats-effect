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

package cats.effect
package internals

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

/**
 * INTERNAL API - utilities for dealing with cancelable thunks.
 */
private[effect] object CancelUtils {
  /**
   * Given a list of cancel tokens, cancels all, delaying all
   * exceptions until all references are canceled.
   */
  def cancelAll(cancelables: CancelToken[IO]*): CancelToken[IO] = {
    if (cancelables.isEmpty) {
      IO.unit
    } else IO.suspend {
      val errors = ListBuffer.empty[Throwable]
      val cursor = cancelables.iterator
      var acc = IO.unit

      while (cursor.hasNext) {
        val next = cursor.next()
        acc = acc.flatMap { _ =>
          try {
            next.handleErrorWith { e =>
              errors += e
              IO.unit
            }
          } catch {
            case e if NonFatal(e) =>
              errors += e
              IO.unit
          }
        }
      }

      acc.flatMap { _ =>
        errors.toList match {
          case Nil =>
            IO.unit
          case first :: rest =>
            IO.raiseError(IOPlatform.composeErrors(first, rest:_*))
        }
      }
    }
  }
}
