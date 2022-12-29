/*
 * Copyright 2020-2022 Typelevel
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

import cats.effect.syntax.all._
import cats.syntax.all._
import cats.~>

import java.util.concurrent.atomic.AtomicReference

private final class IODeferred[A] extends Deferred[IO, A] {
  import IODeferred.Sentinel

  private[this] val cell = new AtomicReference[AnyRef](Sentinel)
  private[this] val callbacks = new CallbackStack[Right[Nothing, A]](null)

  def complete(a: A): IO[Boolean] = IO {
    if (cell.compareAndSet(Sentinel, a.asInstanceOf[AnyRef])) {
      val _ = callbacks(Right(a), false)
      callbacks.lazySet(null) // avoid leaks
      true
    } else {
      false
    }
  }

  def get: IO[A] = IO defer {
    val back = cell.get()

    if (back eq Sentinel) {
      IO.cont[A, A](new Cont[IO, A, A] {
        def apply[G[_]: MonadCancelThrow] = {
          (cb: Either[Throwable, A] => Unit, get: G[A], lift: IO ~> G) =>
            MonadCancel[G] uncancelable { poll =>
              val gga = lift {
                IO {
                  val handle = callbacks.push(cb)

                  val back = cell.get()
                  if (back eq Sentinel) {
                    poll(get).onCancel(lift(IO(handle.clearCurrent())))
                  } else {
                    handle.clearCurrent()
                    back.asInstanceOf[A].pure[G]
                  }
                }
              }

              gga.flatten
            }
        }
      })
    } else {
      IO.pure(back.asInstanceOf[A])
    }
  }

  def tryGet: IO[Option[A]] = IO {
    val back = cell.get()
    if (back eq Sentinel)
      None
    else
      Some(back.asInstanceOf[A])
  }
}

private object IODeferred {
  private val Sentinel = new AnyRef
}
