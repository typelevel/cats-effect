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

import cats.effect.IO.ContextSwitch
import cats.effect.{CancelToken, ExitCase, IO}
import cats.effect.internals.TrampolineEC.immediate

import scala.concurrent.{ExecutionContext, Promise}
import scala.util.control.NonFatal
import java.util.concurrent.atomic.AtomicBoolean

private[effect] object IOBracket {

  /**
   * Implementation for `IO.bracketCase`.
   */
  def apply[A, B](acquire: IO[A])(use: A => IO[B])(release: (A, ExitCase[Throwable]) => IO[Unit]): IO[B] =
    IO.Async { (conn, cb) =>
      // Placeholder for the future finalizer
      val deferredRelease = ForwardCancelable()
      conn.push(deferredRelease.cancel)
      // Race-condition check, avoiding starting the bracket if the connection
      // was cancelled already, to ensure that `cancel` really blocks if we
      // start `acquire` — n.b. `isCanceled` is visible here due to `push`
      if (!conn.isCanceled) {
        // Note `acquire` is uncancelable due to usage of `IORunLoop.start`
        // (in other words it is disconnected from our IOConnection)
        IORunLoop.start[A](acquire, new BracketStart(use, release, conn, deferredRelease, cb))
      } else {
        deferredRelease.complete(IO.unit)
      }
    }

  // Internals of `IO.bracketCase`.
  final private class BracketStart[A, B](
    use: A => IO[B],
    release: (A, ExitCase[Throwable]) => IO[Unit],
    conn: IOConnection,
    deferredRelease: ForwardCancelable,
    cb: Callback.T[B]
  ) extends (Either[Throwable, A] => Unit)
      with Runnable {
    // This runnable is a dirty optimization to avoid some memory allocations;
    // This class switches from being a Callback to a Runnable, but relies on
    // the internal IO callback protocol to be respected (called at most once)
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
        val frame = new BracketReleaseFrame[A, B](a, release)

        // Registering our cancelable token ensures that in case
        // cancellation is detected, `release` gets called
        deferredRelease.complete(frame.cancel)

        // Check if IO wasn't already cancelled in acquire
        if (!conn.isCanceled) {
          val onNext = {
            val fb =
              try use(a)
              catch { case NonFatal(e) => IO.raiseError(e) }
            fb.flatMap(frame)
          }
          // Actual execution
          IORunLoop.startCancelable(onNext, conn, cb)
        }

      case error @ Left(_) =>
        deferredRelease.complete(IO.unit)
        cb(error.asInstanceOf[Either[Throwable, B]])
    }
  }

  /**
   * Implementation for `IO.guaranteeCase`.
   */
  def guaranteeCase[A](source: IO[A], release: ExitCase[Throwable] => IO[Unit]): IO[A] =
    IO.Async { (conn, cb) =>
      // Light async boundary, otherwise this will trigger a StackOverflowException
      ec.execute(new Runnable {
        def run(): Unit = {
          val frame = new EnsureReleaseFrame[A](release)
          val onNext = source.flatMap(frame)
          // Registering our cancelable token ensures that in case
          // cancellation is detected, `release` gets called
          conn.push(frame.cancel)
          // Race condition check, avoiding starting `source` in case
          // the connection was already cancelled — n.b. we don't need
          // to trigger `release` otherwise, because it already happened
          if (!conn.isCanceled) {
            IORunLoop.startCancelable(onNext, conn, cb)
          }
        }
      })
    }

  final private class BracketReleaseFrame[A, B](a: A, releaseFn: (A, ExitCase[Throwable]) => IO[Unit])
      extends BaseReleaseFrame[A, B] {
    def release(c: ExitCase[Throwable]): CancelToken[IO] =
      releaseFn(a, c)
  }

  final private class EnsureReleaseFrame[A](releaseFn: ExitCase[Throwable] => IO[Unit])
      extends BaseReleaseFrame[Unit, A] {
    def release(c: ExitCase[Throwable]): CancelToken[IO] =
      releaseFn(c)
  }

  abstract private class BaseReleaseFrame[A, B] extends IOFrame[B, IO[B]] {
    // Guard used for thread-safety, to ensure the idempotency
    // of the release; otherwise `release` can be called twice
    private[this] val waitsForResult = new AtomicBoolean(true)
    private[this] val p: Promise[Unit] = Promise()

    def release(c: ExitCase[Throwable]): CancelToken[IO]

    private def applyRelease(e: ExitCase[Throwable]): IO[Unit] =
      IO.suspend {
        if (waitsForResult.compareAndSet(true, false))
          release(e)
            .redeemWith[Unit](ex => IO(p.success(())).flatMap(_ => IO.raiseError(ex)), _ => IO { p.success(()); () })
        else
          IOFromFuture.apply(p.future)
      }

    final val cancel: CancelToken[IO] =
      applyRelease(ExitCase.Canceled).uncancelable

    final def recover(e: Throwable): IO[B] =
      // Unregistering cancel token, otherwise we can have a memory leak;
      // N.B. conn.pop() happens after the evaluation of `release`, because
      // otherwise we might have a conflict with the auto-cancellation logic
      ContextSwitch(applyRelease(ExitCase.error(e)), makeUncancelable, disableUncancelableAndPop)
        .flatMap(new ReleaseRecover(e))

    final def apply(b: B): IO[B] =
      // Unregistering cancel token, otherwise we can have a memory leak
      // N.B. conn.pop() happens after the evaluation of `release`, because
      // otherwise we might have a conflict with the auto-cancellation logic
      ContextSwitch(applyRelease(ExitCase.complete), makeUncancelable, disableUncancelableAndPop)
        .map(_ => b)
  }

  final private class ReleaseRecover(e: Throwable) extends IOFrame[Unit, IO[Nothing]] {
    def recover(e2: Throwable): IO[Nothing] = {
      // Logging the error somewhere, because exceptions
      // should never be silent
      Logger.reportFailure(e2)
      IO.raiseError(e)
    }

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
