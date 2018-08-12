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

import cats.effect.{CancelToken, ExitCase, IO}
import scala.util.control.NonFatal

private[effect] object IOBracket {
  /**
   * Implementation for `IO.bracket`.
   */
  def apply[A, B](acquire: IO[A])
    (use: A => IO[B])
    (release: (A, ExitCase[Throwable]) => IO[Unit]): IO[B] = {

    IO.Async { (conn, cb) =>
      // Doing manual plumbing; note that `acquire` here cannot be
      // cancelled due to executing it via `IORunLoop.start`
      IORunLoop.start[A](acquire, {
        case Right(a) =>
          val frame = new ReleaseFrame[A, B](a, release, conn)
          val onNext = {
            val fb = try use(a) catch { case NonFatal(e) => IO.raiseError(e) }
            fb.flatMap(frame)
          }
          // Registering our cancelable token ensures that in case
          // cancelation is detected, `release` gets called
          conn.push(frame.cancel)
          // Actual execution
          IORunLoop.startCancelable(onNext, conn, cb)

        case error @ Left(_) =>
          cb(error.asInstanceOf[Either[Throwable, B]])
      })
    }
  }

  /**
   * Implementation for `IO.ensureCase`.
   */
  def guaranteeCase[A](source: IO[A], release: ExitCase[Throwable] => IO[Unit]): IO[A] = {
    apply(IO.unit)(_ => source)((_, ec) => release(ec))
  }

  private final class ReleaseFrame[A, B](
    a: A,
    release: (A, ExitCase[Throwable]) => IO[Unit],
    conn: IOConnection)
    extends IOFrame[B, IO[B]] {

    val cancel: CancelToken[IO] =
      release(a, ExitCase.Canceled).uncancelable

    def recover(e: Throwable): IO[B] = {
      // Unregistering cancel token, otherwise we can have a memory leak;
      // N.B. this piece of logic does not work if IO is auto-cancelable
      conn.pop()
      release(a, ExitCase.error(e))
        .uncancelable
        .flatMap(new ReleaseRecover(e))
    }

    def apply(b: B): IO[B] = {
      // Unregistering cancel token, otherwise we can have a memory leak
      // N.B. this piece of logic does not work if IO is auto-cancelable
      conn.pop()
      release(a, ExitCase.complete)
        .uncancelable
        .map(_ => b)
    }
  }

  private final class ReleaseRecover(e: Throwable)
    extends IOFrame[Unit, IO[Nothing]] {

    def recover(e2: Throwable): IO[Nothing] =
      IO.raiseError(IOPlatform.composeErrors(e, e2))

    def apply(a: Unit): IO[Nothing] =
      IO.raiseError(e)
  }
}
