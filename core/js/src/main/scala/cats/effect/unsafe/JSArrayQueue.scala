/*
 * Copyright 2020-2024 Typelevel
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

package cats.effect.unsafe

import scala.scalajs.js

/**
 * A JS-Array backed circular buffer FIFO queue. It is careful to grow the buffer only using
 * `push` to avoid creating "holes" on V8 (this is a known shortcoming of the Scala.js
 * `j.u.ArrayDeque` implementation).
 */
private final class JSArrayQueue[A] {

  private[this] val buffer = js.Array[A](null.asInstanceOf[A])

  private[this] var startIndex: Int = 0
  private[this] var endIndex: Int = 1
  private[this] var empty: Boolean = true

  @inline def isEmpty(): Boolean = empty

  @inline def take(): A = {
    val a = buffer(startIndex)
    buffer(startIndex) = null.asInstanceOf[A]
    startIndex += 1
    if (startIndex == endIndex)
      empty = true
    if (startIndex >= buffer.length)
      startIndex = 0
    a
  }

  @inline def offer(a: A): Unit = {
    growIfNeeded()
    endIndex += 1
    if (endIndex > buffer.length)
      endIndex = 1
    buffer(endIndex - 1) = a
    empty = false
  }

  @inline private[this] def growIfNeeded(): Unit =
    if (!empty) { // empty queue always has capacity >= 1
      if (startIndex == 0 && endIndex == buffer.length) {
        buffer.push(null.asInstanceOf[A])
        ()
      } else if (startIndex == endIndex) {
        var i = 0
        while (i < endIndex) {
          buffer.push(buffer(i))
          buffer(i) = null.asInstanceOf[A]
          i += 1
        }
        endIndex = buffer.length
      }
    }

  @inline def foreach(f: A => Unit): Unit =
    if (empty) ()
    else if (startIndex < endIndex) { // consecutive in middle of buffer
      var i = startIndex
      while (i < endIndex) {
        f(buffer(i))
        i += 1
      }
    } else { // split across tail and init of buffer
      var i = startIndex
      while (i < buffer.length) {
        f(buffer(i))
        i += 1
      }
      i = 0
      while (i < endIndex) {
        f(buffer(i))
        i += 1
      }
    }

}
