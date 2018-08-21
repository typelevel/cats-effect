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

import cats.effect.IO.ContextSwitch
import cats.effect.{CancelToken, ExitCase, IO}
import cats.effect.internals.TrampolineEC.immediate
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

private[effect] object IOBracket {
  /**
   * Implementation for `IO.bracketCase`.
   */
  def apply[A, B](acquire: IO[A])
    (use: A => IO[B])
    (release: (A, ExitCase[Throwable]) => IO[Unit]): IO[B] = {

    IO.Async { (conn, cb) =>
      // Doing manual plumbing; note that `acquire` here cannot be
      // cancelled due to executing it via `IORunLoop.start`
      IORunLoop.start[A](acquire, new BracketStart(use, release, conn, cb))
    }
  }

  // Internals of `IO.bracketCase`.
  private final class BracketStart[A, B](
    use: A => IO[B],
    release: (A, ExitCase[Throwable]) => IO[Unit],
    conn: IOConnection,
    cb: Callback.T[B])
    extends (Either[Throwable, A] => Unit) with Runnable {

    private[this] var result: Either[Throwable, A] = _

    def apply(ea: Either[Throwable, A]): Unit = {
      if (result ne null) {
        throw new IllegalStateException("callback called multiple times!")
      }
      // Introducing a light async boundary, otherwise executing the required
      // logic directly will yield a StackOverflowException
      result = ea
      ec.execute(this)
    }

    def run(): Unit = result match {
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
    }
  }

  /**
   * Implementation for `IO.guaranteeCase`.
   */
  def guaranteeCase[A](source: IO[A], release: ExitCase[Throwable] => IO[Unit]): IO[A] = {
    IO.Async { (conn, cb) =>
      // Light async boundary, otherwise this will trigger a StackOverflowException
      ec.execute(new Runnable {
        def run(): Unit = {
          val frame = new EnsureReleaseFrame[A](release, conn)
          val onNext = source.flatMap(frame)
          // Registering our cancelable token ensures that in case
          // cancellation is detected, `release` gets called
          conn.push(frame.cancel)
          // Actual execution
          IORunLoop.startCancelable(onNext, conn, cb)
        }
      })
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
      // N.B. conn.pop() happens after the evaluation of `release`, because
      // otherwise we might have a conflict with the auto-cancellation logic
      ContextSwitch(release(ExitCase.error(e)), makeUncancelable, disableUncancelableAndPop)
        .flatMap(new ReleaseRecover(e))
    }

    final def apply(b: B): IO[B] = {
      // Unregistering cancel token, otherwise we can have a memory leak
      // N.B. conn.pop() happens after the evaluation of `release`, because
      // otherwise we might have a conflict with the auto-cancellation logic
      ContextSwitch(release(ExitCase.complete), makeUncancelable, disableUncancelableAndPop)
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

  /**
   * Trampolined execution context used to preserve stack-safety.
   */
  private[this] val ec: ExecutionContext = immediate

  private[this] val makeUncancelable: IOConnection => IOConnection =
    _ => IOConnection.uncancelable

  private[this] val disableUncancelableAndPop: (Any, Throwable, IOConnection, IOConnection) => IOConnection =
    (_, _, old, _) => {
      old.pop()
      old
    }
}
