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

package cats.effect.internals

import cats.effect.{Fiber, IO, ContextShift}
import cats.implicits._
import scala.concurrent.Promise

private[effect] object IOStart {
  /**
   * Implementation for `IO.start`.
   */
  def apply[A](cs: ContextShift[IO], fa: IO[A]): IO[Fiber[IO, A]] = {
    val start: Start[Fiber[IO, A]] = (_, cb) => {
      // Memoization
      val p = Promise[Either[Throwable, A]]()

      // Starting the source `IO`, with a new connection, because its
      // cancellation is now decoupled from our current one
      val conn2 = IOConnection()
      IORunLoop.startCancelable(IOForkedStart(fa, cs), conn2, p.success)

      cb(Right(fiber(p, conn2)))
    }
    IO.Async(start, trampolineAfter = true)
  }

  private[internals] def fiber[A](p: Promise[Either[Throwable, A]], conn: IOConnection): Fiber[IO, A] =
    Fiber(IOFromFuture(p.future).rethrow, conn.cancel)
}
