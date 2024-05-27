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

package cats.effect

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

private final class IODeferred[A] extends Deferred[IO, A] {

  private[this] val initial: IO[A] = {
    val await = IO.asyncCheckAttempt[A] { cb =>
      IO {
        val handle = callbacks.push(cb)

        def clear(): Unit = {
          val removed = callbacks.clearHandle(handle)
          if (!removed) {
            val clearCount = clearCounter.incrementAndGet()
            if ((clearCount & (clearCount - 1)) == 0) { // power of 2
              clearCounter.addAndGet(-callbacks.pack(clearCount))
              ()
            }
          }
        }

        val back = cell.get()
        if (back eq initial) {
          Left(Some(IO(clear())))
        } else {
          clear()
          Right(back.asInstanceOf[IO.Pure[A]].value)
        }
      }
    }

    IO.defer {
      val back = cell.get()
      if (back eq initial)
        await
      else
        back
    }
  }

  private[this] val cell = new AtomicReference(initial)
  private[this] val callbacks = CallbackStack.of[Right[Nothing, A]](null)
  private[this] val clearCounter = new AtomicInteger

  def complete(a: A): IO[Boolean] = IO {
    if (cell.compareAndSet(initial, IO.pure(a))) {
      val _ = callbacks(Right(a))
      true
    } else {
      false
    }
  }

  def get: IO[A] = cell.get()

  def tryGet: IO[Option[A]] = IO {
    val back = cell.get()
    if (back eq initial)
      None
    else
      Some(back.asInstanceOf[IO.Pure[A]].value)
  }
}

private object IODeferred // bincompat shim
