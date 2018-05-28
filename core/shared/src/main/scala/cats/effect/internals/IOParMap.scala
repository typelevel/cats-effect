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

import cats.syntax.apply._
import cats.effect.{IO, Timer}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

private[effect] object IOParMap {
  import Callback.{Type => Callback}

  /** Implementation for `parMap2`. */
  def apply[A, B, C](timer: Timer[IO], fa: IO[A], fb: IO[B])(f: (A, B) => C): IO[C] = {
    val start: Start[C] = (conn, cb) => {
      // For preventing stack-overflow errors; using a
      // trampolined execution context, so no thread forks
      implicit val ec: ExecutionContext = TrampolineEC.immediate

      // Light async boundary to prevent SO errors
      ec.execute(new Runnable {
        /**
         * State synchronized by an atomic reference. Possible values:
         *
         *  - null: none of the 2 tasks have finished yet
         *  - Left(a): the left task is waiting for the right one
         *  - Right(a): the right task is waiting for the left one
         *  - Throwable: an error was triggered
         *
         * Note - `getAndSet` is used for modifying this atomic, so the
         * final state (both are finished) is implicit.
         */
        private[this] val state = new AtomicReference[AnyRef]()

        def run(): Unit = {
          val connA = IOConnection()
          val connB = IOConnection()

          // Composite cancelable that cancels both.
          // NOTE: conn.pop() happens when cb gets called!
          conn.pushPair(connA, connB)

          IORunLoop.startCancelable(timer.shift *> fa, connA, callbackA(connB))
          IORunLoop.startCancelable(timer.shift *> fb, connB, callbackB(connA))
        }

        /** Callback for the left task. */
        def callbackA(connB: IOConnection): Callback[A] = {
          case Left(e) => sendError(connB, e)
          case Right(a) =>
            // Using Java 8 platform intrinsics
            state.getAndSet(Left(a)) match {
              case null => () // wait for B
              case Right(b) =>
                complete(a, b.asInstanceOf[B])
              case _: Throwable => ()
              case left =>
                // $COVERAGE-OFF$
                throw new IllegalStateException(s"parMap: $left")
              // $COVERAGE-ON$
            }
        }

        /** Callback for the right task. */
        def callbackB(connA: IOConnection): Callback[B] = {
          case Left(e) => sendError(connA, e)
          case Right(b) =>
            // Using Java 8 platform intrinsics
            state.getAndSet(Right(b)) match {
              case null => () // wait for A
              case Left(a) =>
                complete(a.asInstanceOf[A], b)
              case _: Throwable => ()
              case right =>
                // $COVERAGE-OFF$
                throw new IllegalStateException(s"parMap: $right")
              // $COVERAGE-ON$
            }
        }

        /** Called when both results are ready. */
        def complete(a: A, b: B): Unit = {
          conn.pop()
          cb(try Right(f(a, b)) catch { case NonFatal(e) => Left(e) })
        }

        /** Called when an error is generated. */
        def sendError(other: IOConnection, e: Throwable): Unit = {
          state.getAndSet(e) match {
            case _: Throwable =>
              Logger.reportFailure(e)
            case null | Left(_) | Right(_) =>
              // Cancels the other before signaling the error
              try other.cancel() finally {
                conn.pop()
                cb(Left(e))
              }
          }
        }
      })
    }

    IO.Async(start, trampolineAfter = true)
  }
}
