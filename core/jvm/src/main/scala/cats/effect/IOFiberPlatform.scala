/*
 * Copyright 2020 Typelevel
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

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import java.util.{concurrent => juc}
import juc.atomic.{AtomicBoolean, AtomicReference}

private[effect] abstract class IOFiberPlatform[A] { this: IOFiber[A] =>

  private[this] val TypeInterruptibleMany = Sync.Type.InterruptibleMany

  protected final def interruptibleImpl(
      cur: IO.Blocking[Any],
      blockingEc: ExecutionContext): IO[Any] = {
    // InterruptibleMany | InterruptibleOnce

    /*
     * Coordination cases:
     *
     * 1. Action running, but finalizer not yet registered
     * 2. Action running, finalizer registered
     * 3. Action running, finalizer firing
     * 4. Action completed, finalizer registered
     * 5. Action completed, finalizer firing
     * 6. Action completed, finalizer unregistered
     */

    val many = cur.hint eq TypeInterruptibleMany

    IO.async[Any] { nextCb =>
      for {
        done <- IO(new AtomicBoolean(false))
        cb <- IO(new AtomicReference[() => Unit](null))

        canInterrupt <- IO(new juc.Semaphore(0))

        target <- IO uncancelable { _ =>
          IO.async_[Thread] { initCb =>
            blockingEc execute { () =>
              initCb(Right(Thread.currentThread()))

              val result =
                try {
                  canInterrupt.release()
                  val back = Right(cur.thunk())

                  // this is why it has to be a semaphore rather than an atomic boolean
                  // this needs to hard-block if we're in the process of being interrupted
                  canInterrupt.acquire()
                  back
                } catch {
                  case _: InterruptedException =>
                    if (!many) {
                      val cb0 = cb.get()
                      if (cb0 != null) {
                        cb0()
                      }
                    }

                    null

                  case NonFatal(t) =>
                    Left(t)
                } finally {
                  canInterrupt.tryAcquire()
                  done.set(true)
                }

              if (result != null) {
                nextCb(result)
              }
            }
          }
        }
      } yield {
        Some {
          IO async { finCb =>
            val trigger = IO {
              if (!many) {
                cb.set(() => finCb(Right(())))
              }

              // if done is false, and we can't get the semaphore, it means
              // that the action hasn't *yet* started, so we busy-wait for it
              var break = true
              while (break && !done.get()) {
                if (canInterrupt.tryAcquire()) {
                  try {
                    target.interrupt()
                    break = false
                  } finally {
                    canInterrupt.release()
                  }
                }
              }
            }

            val repeat = if (many) {
              IO blocking {
                while (!done.get()) {
                  if (canInterrupt.tryAcquire()) {
                    try {
                      while (!done.get()) {
                        target.interrupt() // it's hammer time!
                      }
                    } finally {
                      canInterrupt.release()
                    }
                  }
                }

                finCb(Right(()))
              }
            } else {
              IO.unit
            }

            (trigger *> repeat).as(None)
          }
        }
      }
    }
  }
}
