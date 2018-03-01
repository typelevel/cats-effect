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

import cats.effect.{Fiber, IO}
import cats.effect.internals.Callback.Extensions
import cats.effect.internals.TrampolineEC.immediate
import scala.concurrent.{ExecutionContext, Promise}

private[effect] object IOStart {

  /**
   * Implementation for `IO.start`.
   */
  def apply[A](fa: IO[A]): IO[Fiber[IO, A]] =
    IO.Async { (_, cb) =>
      implicit val ec: ExecutionContext = immediate
      // Light async boundary
      ec.execute(new Runnable {
        def run(): Unit = {
          // Memoization
          val p = Promise[A]()

          // Starting the source `IO`, with a new connection, because its
          // cancellation is now decoupled from our current one
          val conn2 = IOConnection()
          IORunLoop.startCancelable(fa, conn2, Callback.promise(p))

          // Building a memoized IO - note we cannot use `IO.fromFuture`
          // because we need to link this `IO`'s cancellation with that
          // of the executing task
          val io = IO.Async[A] { (ctx2, cb2) =>
            // Short-circuit for already completed `Future`
            p.future.value match {
              case Some(value) => cb2.completeWithTryAsync(value)
              case None =>
                // Cancellation needs to be linked to the active task
                ctx2.push(conn2.cancel)
                p.future.onComplete { r =>
                  ctx2.pop()
                  cb2.completeWithTry(r)
                }
            }
          }
          // Signal the newly created fiber
          cb(Right(IOFiber(io)))
        }
      })
    }
}
