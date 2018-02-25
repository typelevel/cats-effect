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

package cats.effect.internals

import java.util.concurrent.atomic.AtomicBoolean
import cats.effect.internals.TrampolineEC.immediate
import scala.concurrent.Promise
import scala.util.{Failure, Left, Success, Try}

/**
 * Internal API — utilities for working with `IO.async` callbacks.
 */
private[effect] object Callback {
  type Type[-A] = Either[Throwable, A] => Unit

  /**
   * Builds a callback reference that throws any received
   * error immediately.
   */
  def report[A]: Type[A] =
    reportRef.asInstanceOf[Type[A]]

  private val reportRef = (r: Either[Throwable, _]) =>
    r match {
      case Left(e) => Logger.reportFailure(e)
      case _ => ()
    }

  /** Reusable `Right(())` reference. */
  final val rightUnit = Right(())

  /** Reusable no-op, side-effectful `Function1` reference. */
  final val dummy1: Any => Unit = _ => ()

  /** Builds a callback with async execution. */
  def async[A](cb: Type[A]): Type[A] =
    async(null, cb)

  /**
   * Builds a callback with async execution.
   *
   * Also pops the `Connection` just before triggering
   * the underlying callback.
   */
  def async[A](conn: IOConnection, cb: Type[A]): Type[A] =
    value => immediate.execute(
      new Runnable {
        def run(): Unit = {
          if (conn ne null) conn.pop()
          cb(value)
        }
      })

  /**
   * Callback wrapper used in `IO.async` that:
   *
   *  1. guarantees (thread safe) idempotency
   *  2. triggers light (trampolined) async boundary for stack safety
   *  3. pops the given `Connection` (only if != null)
   *  4. logs extraneous errors after callback was already called once
   */
  def asyncIdempotent[A](conn: IOConnection, cb: Type[A]): Type[A] =
    new AsyncIdempotentCallback[A](conn, cb)

  /**
   * Builds a callback from a standard Scala `Promise`.
   */
  def promise[A](p: Promise[A]): Type[A] = {
    case Right(a) => p.success(a)
    case Left(e) => p.failure(e)
  }

  /** Helpers async callbacks. */
  implicit final class Extensions[-A](val self: Type[A]) extends AnyVal {
    /**
     * Executes the source callback with a light (trampolined) async
     * boundary, meant to protect against stack overflows.
     */
    def async(value: Either[Throwable, A]): Unit =
      async(null, value)

    /**
     * Executes the source callback with a light (trampolined) async
     * boundary, meant to protect against stack overflows.
     *
     * Also pops the given `Connection` before calling the callback.
     */
    def async(conn: IOConnection, value: Either[Throwable, A]): Unit =
      immediate.execute(new Runnable {
        def run(): Unit = {
          if (conn ne null) conn.pop()
          self(value)
        }
      })

    /**
     * Given a standard Scala `Try`, converts it to an `Either` and
     * call the callback with it.
     */
    def completeWithTry(result: Try[A]): Unit =
      self(result match {
        case Success(a) => Right(a)
        case Failure(e) => Left(e)
      })

    /**
     * Like [[completeWithTry]], but with an extra light async boundary.
     */
    def completeWithTryAsync(result: Try[A]): Unit =
      result match {
        case Success(a) => self(Right(a))
        case Failure(e) => self(Left(e))
      }
  }

  private final class AsyncIdempotentCallback[-A](
    conn: IOConnection,
    cb: Either[Throwable, A] => Unit)
    extends (Either[Throwable, A] => Unit) {

    private[this] val canCall = new AtomicBoolean(true)

    def apply(value: Either[Throwable, A]): Unit = {
      if (canCall.getAndSet(false)) {
        immediate.execute(new Runnable {
          def run(): Unit = {
            if (conn ne null) conn.pop()
            cb(value)
          }
        })
      } else value match {
        case Right(_) => ()
        case Left(e) =>
          Logger.reportFailure(e)
      }
    }
  }
}
