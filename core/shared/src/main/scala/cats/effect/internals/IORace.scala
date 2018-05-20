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
import scala.concurrent.Promise
import cats.syntax.apply._
import cats.effect.internals.Callback.{Type => Callback}
import cats.effect.internals.Callback.Extensions

private[effect] object IORace {
  /**
   * Implementation for `IO.race` - could be described with `racePair`,
   * but this way it is more efficient, as we no longer have to keep
   * internal promises.
   */
  def simple[A, B](timer: Timer[IO], lh: IO[A], rh: IO[B]): IO[Either[A, B]] = {
    // Signals successful results
    def onSuccess[T, U](
      isActive: AtomicBoolean,
      other: IOConnection,
      cb: Callback[Either[T, U]],
      r: Either[T, U]): Unit = {

      if (isActive.getAndSet(false)) {
        // First interrupts the other task
        try other.cancel()
        finally cb.async(Right(r))
      }
    }

    def onError[T](
      active: AtomicBoolean,
      cb: Callback.Type[T],
      other: IOConnection,
      err: Throwable): Unit = {

      if (active.getAndSet(false)) {
        try other.cancel()
        finally cb.async(Left(err))
      } else {
        Logger.reportFailure(err)
      }
    }

    IO.cancelable { cb =>
      val active = new AtomicBoolean(true)
      // Cancelable connection for the left value
      val connL = IOConnection()
      // Cancelable connection for the right value
      val connR = IOConnection()

      // Starts concurrent execution for the left value
      IORunLoop.startCancelable[A](timer.shift *> lh, connL, {
        case Right(a) =>
          onSuccess(active, connR, cb, Left(a))
        case Left(err) =>
          onError(active, cb, connR, err)
      })

      // Starts concurrent execution for the right value
      IORunLoop.startCancelable[B](timer.shift *> rh, connR, {
        case Right(b) =>
          onSuccess(active, connL, cb, Right(b))
        case Left(err) =>
          onError(active, cb, connL, err)
      })

      // Composite cancelable that cancels both
      IO(Cancelable.cancelAll(connL.cancel, connR.cancel))
    }
  }

  /**
   * Implementation for `IO.racePair`
   */
  def pair[A, B](timer: Timer[IO], lh: IO[A], rh: IO[B]): IO[Either[(A, Fiber[IO, B]), (Fiber[IO, A], B)]] = {
    IO.cancelable { cb =>
      val active = new AtomicBoolean(true)
      // Cancelable connection for the left value
      val connL = IOConnection()
      val promiseL = Promise[Either[Throwable, A]]()
      // Cancelable connection for the right value
      val connR = IOConnection()
      val promiseR = Promise[Either[Throwable, B]]()

      // Starts concurrent execution for the left value
      IORunLoop.startCancelable[A](timer.shift *> lh, connL, {
        case Right(a) =>
          if (active.getAndSet(false))
            cb.async(Right(Left((a, IOFiber.build[B](promiseR, connR)))))
          else
            promiseL.trySuccess(Right(a))

        case Left(err) =>
          if (active.getAndSet(false)) {
            cb.async(Left(err))
            connR.cancel()
          } else {
            promiseL.trySuccess(Left(err))
          }
      })

      // Starts concurrent execution for the right value
      IORunLoop.startCancelable[B](timer.shift *> rh, connR, {
        case Right(b) =>
          if (active.getAndSet(false))
            cb.async(Right(Right((IOFiber.build[A](promiseL, connL), b))))
          else
            promiseR.trySuccess(Right(b))

        case Left(err) =>
          if (active.getAndSet(false)) {
            cb.async(Left(err))
            connL.cancel()
          } else {
            promiseR.trySuccess(Left(err))
          }
      })

      // Composite cancelable that cancels both
      IO(Cancelable.cancelAll(connL.cancel, connR.cancel))
    }
  }
}
