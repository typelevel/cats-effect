/*
 * Copyright 2017 Typelevel
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

import java.util.concurrent.atomic.AtomicReference
import cats.effect.IO
import cats.effect.util.CompositeException
import scala.concurrent.ExecutionContext

private[effect] object IOParMap {
  type Attempt[A] = Either[Throwable, A]
  type State[A, B] = Either[Attempt[A], Attempt[B]]

  def apply[A, B, C](fa: IO[A], fb: IO[B])(f: (A, B) => C): IO[C] =
    IO.async { cb =>
      // For preventing stack-overflow errors; using a
      // trampolined execution context, so no thread forks
      implicit val ec: ExecutionContext = TrampolineEC.immediate

      // Light async boundary to prevent SO errors
      ec.execute(new Runnable {
        def run(): Unit = {
          val state = new AtomicReference[State[A, B]]()

          def complete(ra: Attempt[A], rb: Attempt[B]): Unit =
            // Second async boundary needed just before the callback
            ec.execute(new Runnable {
              def run(): Unit = ra match {
                case Right(a) =>
                  rb match {
                    case Right(b) =>
                      cb(try Right(f(a, b)) catch { case NonFatal(e) => Left(e) })
                    case error @ Left(_) =>
                      cb(error.asInstanceOf[Left[Throwable, C]])
                  }
                case left @ Left(e1) =>
                  rb match {
                    case Right(_) =>
                      cb(left.asInstanceOf[Left[Throwable, C]])
                    case Left(e2) =>
                      // Signaling both errors
                      cb(Left(CompositeException(e1, e2)))
                  }
              }
            })

          // First execution
          fa.unsafeRunAsync { attemptA =>
            // Using Java 8 platform intrinsics
            state.getAndSet(Left(attemptA)) match {
              case null => () // wait for B
              case Right(attemptB) => complete(attemptA, attemptB)
              case left =>
                // $COVERAGE-OFF$
                throw new IllegalStateException(s"parMap: $left")
                // $COVERAGE-ON$
            }
          }
          // Second execution
          fb.unsafeRunAsync { attemptB =>
            // Using Java 8 platform intrinsics
            state.getAndSet(Right(attemptB)) match {
              case null => () // wait for A
              case Left(attemptA) => complete(attemptA, attemptB)
              case right =>
                // $COVERAGE-OFF$
                throw new IllegalStateException(s"parMap: $right")
                // $COVERAGE-ON$
            }
          }
        }
      })
    }
}
