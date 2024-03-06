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

package cats.effect.std

import scala.annotation.tailrec

import java.util.concurrent.atomic.AtomicReference

private final class UnsafeUnbounded[A] {
  private[this] val first = new AtomicReference[Cell]
  private[this] val last = new AtomicReference[Cell]
  private[this] val FailureSignal = cats.effect.std.FailureSignal // prefetch

  def size(): Int = {
    var current = first.get()
    var count = 0
    while (current != null) {
      count += 1
      current = current.get()
    }
    count
  }

  def put(data: A): () => Unit = {
    val cell = new Cell(data)

    val prevLast = last.getAndSet(cell)

    if (prevLast eq null)
      first.set(cell)
    else
      prevLast.set(cell)

    cell
  }

  @tailrec
  def take(): A = {
    val taken = first.get()
    if (taken ne null) {
      val next = taken.get()
      if (first.compareAndSet(taken, next)) { // WINNING
        if ((next eq null) && !last.compareAndSet(taken, null)) {
          // we emptied the first, but someone put at the same time
          // in this case, they might have seen taken in the last slot
          // at which point they would *not* fix up the first pointer
          // instead of fixing first, they would have written into taken
          // so we fix first for them. but we might be ahead, so we loop
          // on taken.get() to wait for them to make it not-null

          var next2 = taken.get()
          while (next2 eq null) {
            next2 = taken.get()
          }

          first.set(next2)
        }

        val ret = taken.data()
        taken() // Attempt to clear out data we've consumed
        ret
      } else {
        take() // We lost, try again
      }
    } else {
      if (last.get() ne null) {
        take() // Waiting for prevLast.set(cell), so recurse
      } else {
        throw FailureSignal
      }
    }
  }

  def debug(): String = {
    val f = first.get()

    if (f == null) {
      "[]"
    } else {
      f.debug()
    }
  }

  private final class Cell(private[this] final var _data: A)
      extends AtomicReference[Cell]
      with (() => Unit) {

    def data(): A = _data

    final override def apply(): Unit = {
      _data = null.asInstanceOf[A] // You want a lazySet here
    }

    def debug(): String = {
      val tail = get()
      s"${_data} -> ${if (tail == null) "[]" else tail.debug()}"
    }
  }
}
