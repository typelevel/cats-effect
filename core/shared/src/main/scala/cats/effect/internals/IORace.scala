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

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Promise

private[effect] object IORace {

  /**
   * Implementation for `IO.race` - could be described with `racePair`,
   * but this way it is more efficient, as we no longer have to keep
   * internal promises.
   */
  def simple[A, B](cs: ContextShift[IO], lh: IO[A], rh: IO[B]): IO[Either[A, B]] = {
    // Signals successful results
    def onSuccess[T, U](isActive: AtomicBoolean,
                        main: IOConnection,
                        other: IOConnection,
                        cb: Callback.T[Either[T, U]],
                        r: Either[T, U]): Unit =
      if (isActive.getAndSet(false)) {
        // First interrupts the other task
        other.cancel.unsafeRunAsync { r2 =>
          main.pop()
          cb(Right(r))
          maybeReport(r2)
        }
      }

    def onError[T](active: AtomicBoolean,
                   cb: Callback.T[T],
                   main: IOConnection,
                   other: IOConnection,
                   err: Throwable): Unit =
      if (active.getAndSet(false)) {
        other.cancel.unsafeRunAsync { r2 =>
          main.pop()
          maybeReport(r2)
          cb(Left(err))
        }
      } else {
        Logger.reportFailure(err)
      }

    val start: Start[Either[A, B]] = (conn, cb) => {
      val active = new AtomicBoolean(true)
      // Cancelable connection for the left value
      val connL = IOConnection()
      // Cancelable connection for the right value
      val connR = IOConnection()
      // Registers both for cancellation — gets popped right
      // before callback is invoked in onSuccess / onError
      conn.pushPair(connL, connR)

      // Starts concurrent execution for the left value
      IORunLoop.startCancelable[A](IOForkedStart(lh, cs), connL, {
        case Right(a) =>
          onSuccess(active, conn, connR, cb, Left(a))
        case Left(err) =>
          onError(active, cb, conn, connR, err)
      })

      // Starts concurrent execution for the right value
      IORunLoop.startCancelable[B](IOForkedStart(rh, cs), connR, {
        case Right(b) =>
          onSuccess(active, conn, connL, cb, Right(b))
        case Left(err) =>
          onError(active, cb, conn, connL, err)
      })
    }

    IO.Async(start, trampolineAfter = true)
  }

  type Pair[A, B] = Either[(A, Fiber[IO, B]), (Fiber[IO, A], B)]

  /**
   * Implementation for `IO.racePair`
   */
  def pair[A, B](cs: ContextShift[IO], lh: IO[A], rh: IO[B]): IO[Pair[A, B]] = {
    val start: Start[Pair[A, B]] = (conn, cb) => {
      val active = new AtomicBoolean(true)
      // Cancelable connection for the left value
      val connL = IOConnection()
      val promiseL = Promise[Either[Throwable, A]]()
      // Cancelable connection for the right value
      val connR = IOConnection()
      val promiseR = Promise[Either[Throwable, B]]()

      // Registers both for cancellation — gets popped right
      // before callback is invoked in onSuccess / onError
      conn.pushPair(connL, connR)

      // Starts concurrent execution for the left value
      IORunLoop.startCancelable[A](
        IOForkedStart(lh, cs),
        connL, {
          case Right(a) =>
            if (active.getAndSet(false)) {
              conn.pop()
              cb(Right(Left((a, IOStart.fiber[B](promiseR, connR)))))
            } else {
              promiseL.trySuccess(Right(a))
              ()
            }
          case Left(err) =>
            if (active.getAndSet(false)) {
              connR.cancel.unsafeRunAsync { r2 =>
                conn.pop()
                maybeReport(r2)
                cb(Left(err))
              }
            } else {
              promiseL.trySuccess(Left(err))
              ()
            }
        }
      )

      // Starts concurrent execution for the right value
      IORunLoop.startCancelable[B](
        IOForkedStart(rh, cs),
        connR, {
          case Right(b) =>
            if (active.getAndSet(false)) {
              conn.pop()
              cb(Right(Right((IOStart.fiber[A](promiseL, connL), b))))
            } else {
              promiseR.trySuccess(Right(b))
              ()
            }

          case Left(err) =>
            if (active.getAndSet(false)) {
              connL.cancel.unsafeRunAsync { r2 =>
                conn.pop()
                maybeReport(r2)
                cb(Left(err))
              }
            } else {
              promiseR.trySuccess(Left(err))
              ()
            }
        }
      )
    }

    IO.Async(start, trampolineAfter = true)
  }

  private[this] def maybeReport(r: Either[Throwable, _]): Unit =
    r match {
      case Left(e) => Logger.reportFailure(e)
      case _       => ()
    }
}
