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

import java.util.concurrent.atomic.AtomicBoolean
import cats.effect.IO.{Async, ContextSwitch}
import scala.concurrent.ExecutionContext
import scala.util.Left

private[effect] object IOCancel {
  /**
   * Implementation for `IO.cancel`.
   */
  def raise[A](fa: IO[A], e: Throwable): IO[A] =
    Async { (conn, cb) =>
      val canCall = new AtomicBoolean(true)
      // We need a special connection because the main one will be reset on
      // cancellation and this can interfere with the cancellation of `fa`
      val connChild = IOConnection()
      // Registering a special cancelable that will trigger error on cancel.
      // Note the pair `conn.pop` happens in `RaiseCallback`.
      conn.push(raiseCancelable(canCall, conn, connChild, cb, e))
      // Registering a callback that races against the cancelable we
      // registered above
      val cb2 = new RaiseCallback[A](canCall, conn, cb)
      // Execution
      IORunLoop.startCancelable(fa, connChild, cb2)
    }

  /** Implementation for `IO.uncancelable`. */
  def uncancelable[A](fa: IO[A]): IO[A] =
    ContextSwitch(fa, makeUncancelable, disableUncancelable)

  private final class RaiseCallback[A](
    active: AtomicBoolean,
    conn: IOConnection,
    cb: Callback.T[A])
    extends Callback.T[A] with Runnable {

    private[this] var value: Either[Throwable, A] = _
    def run(): Unit = cb(value)

    def apply(value: Either[Throwable, A]): Unit =
      if (active.getAndSet(false)) {
        conn.pop()
        this.value = value
        ec.execute(this)
      } else value match {
        case Left(e) => Logger.reportFailure(e)
        case _ => ()
      }
  }


  private def raiseCancelable[A](
    active: AtomicBoolean,
    conn: IOConnection,
    conn2: IOConnection,
    cb: Either[Throwable, A] => Unit,
    e: Throwable): CancelToken[IO] = {

    IO.async { onFinish =>
      // Race-condition guard: in case the source was completed and the
      // result already signaled, then no longer allow the finalization
      if (active.getAndSet(false)) {
        // Trigger cancellation
        conn2.cancel.unsafeRunAsync { r =>
          // Reset the original cancellation token
          conn.tryReactivate()
          // Signal completion of the cancellation logic, such that the
          // logic waiting for the completion of finalizers can continue
          onFinish(r)
          // Continue with the bind continuation of `onCancelRaiseError`
          cb(Left(e))
        }
      } else {
        onFinish(Callback.rightUnit)
      }
    }
  }

  /** Trampolined execution context. */
  private[this] val ec: ExecutionContext = TrampolineEC.immediate

  /** Internal reusable reference. */
  private[this] val makeUncancelable: IOConnection => IOConnection =
    _ => IOConnection.uncancelable
  private[this] val disableUncancelable: (Any, Throwable, IOConnection, IOConnection) => IOConnection =
    (_, _, old, _) => old
}
