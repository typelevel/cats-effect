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
          val frame = new BracketReleaseFrame[A, B](a, release, conn)
          val onNext = {
            val fb = try use(a) catch { case NonFatal(e) => IO.raiseError(e) }
            fb.flatMap(frame)
          }
          // Registering our cancelable token ensures that in case
          // cancellation is detected, `release` gets called
          conn.push(frame.cancel)
          // Actual execution
          IORunLoop.startCancelable(onNext, conn, cb)

        case error @ Left(_) =>
          cb(error.asInstanceOf[Either[Throwable, B]])
      })
    }
  }

  /**
   * Implementation for `IO.guaranteeCase`.
   */
  def guaranteeCase[A](source: IO[A], release: ExitCase[Throwable] => IO[Unit]): IO[A] = {
    IO.Async { (conn, cb) =>
      val frame = new EnsureReleaseFrame[A](release, conn)
      val onNext = source.flatMap(frame)
      // Registering our cancelable token ensures that in case
      // cancellation is detected, `release` gets called
      conn.push(frame.cancel)
      // Actual execution
      IORunLoop.startCancelable(onNext, conn, cb)
    }
  }

  private final class BracketReleaseFrame[A, B](
    a: A,
    releaseFn: (A, ExitCase[Throwable]) => IO[Unit],
    conn: IOConnection)
    extends BaseReleaseFrame[A, B](conn) {

    def release(c: ExitCase[Throwable]): CancelToken[IO] =
      releaseFn(a, c)
  }

  private final class EnsureReleaseFrame[A](
    releaseFn: ExitCase[Throwable] => IO[Unit],
    conn: IOConnection)
    extends BaseReleaseFrame[Unit, A](conn) {

    def release(c: ExitCase[Throwable]): CancelToken[IO] =
      releaseFn(c)
  }

  private abstract class BaseReleaseFrame[A, B](conn: IOConnection)
    extends IOFrame[B, IO[B]] {

    def release(c: ExitCase[Throwable]): CancelToken[IO]

    final val cancel: CancelToken[IO] =
      release(ExitCase.Canceled).uncancelable

    final def recover(e: Throwable): IO[B] = {
      // Unregistering cancel token, otherwise we can have a memory leak;
      // N.B. this piece of logic does not work if IO is auto-cancelable
      conn.pop()
      release(ExitCase.error(e))
        .uncancelable
        .flatMap(new ReleaseRecover(e))
    }

    final def apply(b: B): IO[B] = {
      // Unregistering cancel token, otherwise we can have a memory leak
      // N.B. this piece of logic does not work if IO is auto-cancelable
      conn.pop()
      release(ExitCase.complete)
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
