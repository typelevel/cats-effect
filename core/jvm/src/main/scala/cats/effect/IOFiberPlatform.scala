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

import scala.util.control.NonFatal

import java.nio.channels.ClosedByInterruptException
import java.util.{concurrent => juc}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

private[effect] abstract class IOFiberPlatform[A] extends AtomicBoolean(false) {
  this: IOFiber[A] =>

  protected final def interruptibleImpl(cur: IO.Blocking[Any]): IO[Any] = {
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

    val many = cur.hint eq Sync.Type.InterruptibleMany

    // we grab this here rather than in the instance to avoid bloating IOFiber's object header
    val RightUnit = IOFiber.RightUnit

    IO.async[Any] { nextCb =>
      for {
        done <- IO(new AtomicBoolean(false))
        cb <- IO(new AtomicReference[Either[Throwable, Unit] => Unit](null))

        canInterrupt <- IO(new juc.Semaphore(0))
        manyDone <- IO(new AtomicBoolean(false))

        target <- IO uncancelable { _ =>
          IO.async[Thread] { initCb =>
            val action = IO blocking {
              initCb(Right(Thread.currentThread()))

              val result =
                try {
                  canInterrupt.release()

                  val back =
                    try {
                      Right(cur.thunk())
                    } catch {
                      case ex: ClosedByInterruptException => throw ex

                      // this won't suppress InterruptedException:
                      case t if NonFatal(t) => Left(t)
                    }

                  // this is why it has to be a semaphore rather than an atomic boolean
                  // this needs to hard-block if we're in the process of being interrupted
                  // once we acquire this lock, we cannot be interrupted
                  canInterrupt.acquire()

                  if (many) {
                    manyDone.set(true) // in this case, we weren't interrupted
                  }

                  back
                } catch {
                  case _: InterruptedException | _: ClosedByInterruptException =>
                    null
                } finally {
                  canInterrupt.tryAcquire()
                  done.set(true)

                  if (many) {
                    // wait for the hot loop to finish
                    // we can probably also do this with canInterrupt, but that seems confusing
                    // this needs to be a busy-wait otherwise it will be interrupted
                    while (!manyDone.get()) {}
                    Thread.interrupted() // clear the status

                    ()
                  } else {
                    val cb0 = cb.getAndSet(null)
                    if (cb0 != null) {
                      cb0(RightUnit)
                    }
                  }
                }

              if (result != null) {
                nextCb(result)
              }
            }

            action.start.as(None)
          }
        }
      } yield {
        Some {
          IO async { finCb =>
            val trigger = IO.defer {
              if (!many) {
                cb.set(finCb)
              }

              // if done is false, and we can't get the semaphore, it means
              // that the action hasn't *yet* started, so we busy-wait for it

              def busyWait(): IO[Unit] = { // NB: side-effecting, see `defer` above
                if (done.get()) {
                  IO.unit // ok, we're done
                } else if (canInterrupt.tryAcquire()) {
                  try {
                    target.interrupt()
                  } finally {
                    canInterrupt.release()
                  }
                  IO.unit // ok, we've interrupted it
                } else {
                  IO.defer(busyWait()) // retry
                }
              }

              busyWait()
            }

            val repeat = if (many) {

              def reallyTryToInterrupt(): Boolean = {
                if (canInterrupt.tryAcquire()) {
                  try {
                    while (!done.get()) {
                      target.interrupt() // it's hammer time!
                    }
                  } finally {
                    canInterrupt.release()
                  }
                  true // ok
                } else {
                  false // retry
                }
              }

              def loop: IO[Unit] = IO.defer {
                if (done.get() || reallyTryToInterrupt()) IO.unit
                else loop
              }

              loop.guarantee(IO { manyDone.set(true) }) *> IO { finCb(RightUnit) }

            } else {
              IO {
                if (done.get() && cb.get() != null) {
                  // this indicates that the blocking action completed *before* we registered the callback
                  finCb(RightUnit) // ...so we just complete cancelation ourselves
                }
              }
            }

            (trigger *> repeat).as(None)
          }
        }
      }
    }
  }
}
