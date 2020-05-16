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

import cats.effect.IO.{ContextSwitch, RaiseError}
import cats.effect.{CancelToken, ExitCase, IO}

import scala.concurrent.Promise
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.control.NonFatal

private[effect] object IOBracket {

  final private[effect] class BracketUseFrame(
    conn: IOConnection,
    deferredRelease: ForwardCancelable,
    use: Any => IO[Any],
    release: (Any, ExitCase[Throwable]) => IO[Unit]
  ) extends IOFrame[Any, IO[Any]] {

    override def apply(a: Any): IO[Any] =
      IO.suspend {
        conn.unguard()

        val frame = new BracketReleaseFrame(a, release)
        deferredRelease.complete(frame.cancel)

        if (!conn.isCanceled) {
          val onUse =
            try use(a)
            catch { case NonFatal(ex) => RaiseError(ex) }
          onUse.flatMap(frame)
        } else {
          IO.never
        }
      }

    override def recover(e: Throwable): IO[Any] =
      IO.suspend {
        conn.unguard()
        deferredRelease.complete(IO.unit)
        IO.raiseError(e)
      }

  }

  final private[effect] class BracketReleaseFrame(a: Any, releaseFn: (Any, ExitCase[Throwable]) => IO[Unit])
      extends IOFrame[Any, IO[Any]] {
    // Guard used for thread-safety, to ensure the idempotency
    // of the release; otherwise `release` can be called twice
    private[this] val waitsForResult = new AtomicBoolean(true)
    private[this] val p: Promise[Unit] = Promise()

    def release(c: ExitCase[Throwable]): CancelToken[IO] =
      releaseFn(a, c)

    private def applyRelease(e: ExitCase[Throwable]): IO[Unit] =
      IO.suspend {
        if (waitsForResult.compareAndSet(true, false)) {
          release(e)
            .redeemWith[Unit](ex => IO(p.success(())).flatMap(_ => IO.raiseError(ex)), _ => IO { p.success(()); () })
        } else {
          IOFromFuture.apply(p.future)
        }
      }

    final val cancel: CancelToken[IO] =
      applyRelease(ExitCase.Canceled).uncancelable

    final def recover(e: Throwable): IO[Any] =
      // Unregistering cancel token, otherwise we can have a memory leak;
      // N.B. conn.pop() happens after the evaluation of `release`, because
      // otherwise we might have a conflict with the auto-cancellation logic
      ContextSwitch(applyRelease(ExitCase.error(e)), makeUncancelable, disableUncancelableAndPop)
        .flatMap(new ReleaseRecover(e))

    final def apply(a: Any): IO[Any] =
      // Unregistering cancel token, otherwise we can have a memory leak
      // N.B. conn.pop() happens after the evaluation of `release`, because
      // otherwise we might have a conflict with the auto-cancellation logic
      ContextSwitch(applyRelease(ExitCase.complete), makeUncancelable, disableUncancelableAndPop)
        .map(_ => a)
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

  private[this] val makeUncancelable: IOConnection => IOConnection =
    _ => IOConnection.uncancelable

  private[this] val disableUncancelableAndPop: (Any, Throwable, IOConnection, IOConnection) => IOConnection =
    (_, _, old, _) => {
      old.pop()
      old
    }
}
