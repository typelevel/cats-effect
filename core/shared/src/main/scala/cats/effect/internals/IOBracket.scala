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

import cats.effect.{ExitCase, IO}
import scala.concurrent.CancellationException

private[effect] object IOBracket {
  /**
    * Implementation for `IO.bracket`.
    */
  def apply[A, B](acquire: IO[A])
    (use: A => IO[B])
    (release: (A, ExitCase[Throwable]) => IO[Unit]): IO[B] = {

    acquire.uncancelable.flatMap { a =>
      IO.Bind(
        use(a).onCancelRaiseError(cancelException),
        new ReleaseFrame[A, B](a, release))
    }
  }

  /**
   * Implementation for `IO.ensureCase`.
   */
  def guaranteeCase[A](source: IO[A], release: ExitCase[Throwable] => IO[Unit]): IO[A] = {
    IO.Bind(
      source.onCancelRaiseError(cancelException),
      new ReleaseFrame[Unit, A]((), (_, e) => release(e)))
  }

  private final class ReleaseFrame[A, B](a: A,
    release: (A, ExitCase[Throwable]) => IO[Unit])
    extends IOFrame[B, IO[B]] {

    def recover(e: Throwable): IO[B] = {
      if (e ne cancelException)
        release(a, ExitCase.error(e))
          .uncancelable
          .flatMap(new ReleaseRecover(e))
      else
        release(a, ExitCase.canceled)
          .uncancelable
          .flatMap(Function.const(IO.never))
    }

    def apply(b: B): IO[B] =
      release(a, ExitCase.complete)
        .uncancelable
        .map(_ => b)
  }

  private final class ReleaseRecover(e: Throwable)
    extends IOFrame[Unit, IO[Nothing]] {

    def recover(e2: Throwable): IO[Nothing] = {
      // Logging the error somewhere, because exceptions
      // should never be silent
      Logger.reportFailure(e2)
      IO.raiseError(e)
    }

    def apply(a: Unit): IO[Nothing] =
      IO.raiseError(e)
  }

  private[this] val cancelException = new CancellationException("bracket")
}
