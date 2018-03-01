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
import cats.effect.IO.Async
import scala.concurrent.ExecutionContext
import scala.util.Left

private[effect] object IOCancel {
  import Callback.{rightUnit, Type => Callback}

  /** Implementation for `IO.cancel`. */
  def signal[A](fa: IO[A]): IO[Unit] =
    Async { (_, cb) =>
      ec.execute(new Runnable {
        def run(): Unit = {
          // Ironically, in order to describe cancellation as a pure operation
          // we have to actually execute our `IO` task - the implementation passing an
          // IOConnection.alreadyCanceled which will cancel any pushed cancelable
          // tokens along the way and also return `false` on `isCanceled`
          // (relevant for `IO.cancelBoundary`)
          IORunLoop.startCancelable(fa, IOConnection.alreadyCanceled, Callback.report)
          cb(rightUnit)
        }
      })
    }

  /** Implementation for `IO.cancel`. */
  def raise[A](fa: IO[A], e: Throwable): IO[A] =
    Async { (conn, cb) =>
      val canCall = new AtomicBoolean(true)
      // Registering a special cancelable that will trigger error on cancel.
      // Note the pair `conn.pop` happens in `RaiseCallback`.
      conn.push(new RaiseCancelable(canCall, cb, e))

      ec.execute(new Runnable {
        def run(): Unit = {
          val cb2 = new RaiseCallback[A](canCall, conn, cb)
          IORunLoop.startCancelable(fa, conn, cb2)
        }
      })
    }

  /** Implementation for `IO.uncancelable`. */
  def uncancelable[A](fa: IO[A]): IO[A] =
    Async { (_, cb) =>
      // First async (trampolined) boundary
      ec.execute(new Runnable {
        def run(): Unit = {
          // Second async (trampolined) boundary
          val cb2 = Callback.async(cb)
          // By not passing the `Connection`, execution becomes uncancelable
          IORunLoop.start(fa, cb2)
        }
      })
    }

  private final class RaiseCallback[A](
    waitsForResult: AtomicBoolean,
    conn: IOConnection,
    cb: Callback[A])
    extends Callback[A] {

    def apply(value: Either[Throwable, A]): Unit =
      if (waitsForResult.getAndSet(false)) {
        conn.pop()
        ec.execute(new Runnable { def run() = cb(value) })
      } else value match {
        case Left(e) => throw e
        case _ => ()
      }
  }

  private final class RaiseCancelable[A](
    waitsForResult: AtomicBoolean,
    cb: Either[Throwable, A] => Unit,
    e: Throwable)
    extends (() => Unit) {

    def apply(): Unit =
      if (waitsForResult.getAndSet(false)) {
        ec.execute(new Runnable { def run() = cb(Left(e)) })
      }
  }

  /** Trampolined execution context. */
  private[this] val ec: ExecutionContext = TrampolineEC.immediate
}
