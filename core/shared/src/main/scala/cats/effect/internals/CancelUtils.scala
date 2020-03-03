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

package cats.effect
package internals

import scala.collection.mutable.ListBuffer

/**
 * INTERNAL API - utilities for dealing with cancelable thunks.
 */
private[effect] object CancelUtils {

  /**
   * Given a list of cancel tokens, cancels all, delaying all
   * exceptions until all references are canceled.
   */
  def cancelAll(cancelables: CancelToken[IO]*): CancelToken[IO] =
    if (cancelables.isEmpty) {
      IO.unit
    } else {
      IO.suspend {
        cancelAll(cancelables.iterator)
      }
    }

  def cancelAll(cursor: Iterator[CancelToken[IO]]): CancelToken[IO] =
    if (cursor.isEmpty) {
      IO.unit
    } else {
      IO.suspend {
        val frame = new CancelAllFrame(cursor)
        frame.loop()
      }
    }

  // Optimization for `cancelAll`
  final private class CancelAllFrame(cursor: Iterator[CancelToken[IO]]) extends IOFrame[Unit, IO[Unit]] {
    private[this] val errors = ListBuffer.empty[Throwable]

    def loop(): CancelToken[IO] =
      if (cursor.hasNext) {
        cursor.next().flatMap(this)
      } else {
        errors.toList match {
          case Nil =>
            IO.unit
          case first :: rest =>
            // Logging the errors somewhere, because exceptions
            // should never be silent
            rest.foreach(Logger.reportFailure)
            IO.raiseError(first)
        }
      }

    def apply(a: Unit): IO[Unit] =
      loop()

    def recover(e: Throwable): IO[Unit] = {
      errors += e
      loop()
    }
  }
}
