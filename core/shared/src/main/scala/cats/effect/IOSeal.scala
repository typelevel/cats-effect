/*
 * Copyright 2020-2023 Typelevel
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

import cats.effect.kernel.Seal
import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean}

private final class IOSeal extends Seal[IO] {

  private val doAwait = IO.asyncCheckAttempt[Unit] { cb =>
    IO {
      val stack = callbacks.push(cb)
      val handle = stack.currentHandle()

      def clear(): Unit = {
        stack.clearCurrent(handle)
        val clearCount = clearCounter.incrementAndGet()
        if ((clearCount & (clearCount - 1)) == 0) // power of 2
          clearCounter.addAndGet(-callbacks.pack(clearCount))
        ()
      }

      if (isBrokenCell.get()) {
        clear()
        Right(())
      } else
          Left(Some(IO(clear())))
    }
  }

  private[this] val isBrokenCell = new AtomicBoolean(false)
  private[this] val callbacks = CallbackStack[Right[Nothing, Unit]](null)
  private[this] val clearCounter = new AtomicInteger

  def break: IO[Boolean] = IO {
    if (isBrokenCell.compareAndSet(false, true)) {
      val _ = callbacks(Right(()), false)
      callbacks.clear() // avoid leaks
      true
    } else
      false
  }

  def await: IO[Unit] = IO.defer(if (isBrokenCell.get()) IO.unit else doAwait)

  def isBroken: IO[Boolean] = IO(isBrokenCell.get())
}

private object IOSeal // bincompat shim
