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
import cats.effect.internals.Callback.{Type => Callback}
import cats.effect.internals.Callback.Extensions

private[effect] object IORace {
  // Internal API
  private type Pair[A, B] = Either[(A, Fiber[IO, B]), (Fiber[IO, A], B)]

  /**
   * Implementation for `IO.race` - could be described with `racePair`,
   * but this way it is more efficient, as we no longer have to keep
   * internal promises.
   */
  def simple[A, B](lh: IO[A], rh: IO[B]): IO[Either[A, B]] = {
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

    IO.cancelable { cb =>
      val active = new AtomicBoolean(true)
      // Cancelable connection for the left value
      val connL = IOConnection()
      // Cancelable connection for the right value
      val connR = IOConnection()

      // Starts concurrent execution for the left value
      IORunLoop.startCancelable[A](lh, connL, {
        case Right(a) =>
          onSuccess(active, connR, cb, Left(a))
        case Left(err) =>
          onError(active, cb, connR, err)
      })
      // Starts concurrent execution for the right value
      IORunLoop.startCancelable[B](rh, connR, {
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
  def pair[A, B](lh: IO[A], rh: IO[B]): IO[Either[(A, Fiber[IO, B]), (Fiber[IO, A], B)]] = {
    // For signaling successful results
    def onSuccess[T, U](
      isActive: AtomicBoolean,
      cb: Callback.Type[Pair[T, U]],
      p: Promise[Any],
      r: Pair[T, U]): Unit = {

      if (isActive.getAndSet(false))
        cb.async(Right(r))
      else
        p.trySuccess(r)
    }

    IO.cancelable { cb =>
      val active = new AtomicBoolean(true)
      // Cancelable connection for the left value
      val connL = IOConnection()
      val promiseL = Promise[A]()
      // Cancelable connection for the right value
      val connR = IOConnection()
      val promiseR = Promise[B]()

      // Starts concurrent execution for the left value
      IORunLoop.startCancelable[A](lh, connL, {
        case Right(a) =>
          onSuccess(
            active, cb,
            promiseL.asInstanceOf[Promise[Any]],
            Left((a, IOFiber.build(promiseR, connR))))
        case Left(err) =>
          onError(active, cb, connR, err)
      })
      // Starts concurrent execution for the right value
      IORunLoop.startCancelable[B](rh, connR, {
        case Right(b) =>
          onSuccess(
            active, cb,
            promiseR.asInstanceOf[Promise[Any]],
            Right((IOFiber.build(promiseL, connL), b)))
        case Left(err) =>
          onError(active, cb, connL, err)
      })
      // Composite cancelable that cancels both
      IO(Cancelable.cancelAll(connL.cancel, connR.cancel))
    }
  }

  private def onError[A, B](
    isActive: AtomicBoolean,
    cb: Callback.Type[Nothing],
    other: IOConnection,
    err: Throwable): Unit = {

    if (isActive.getAndSet(false)) {
      try other.cancel()
      finally cb(Left(err))
    } else {
      Logger.reportFailure(err)
    }
  }
}
