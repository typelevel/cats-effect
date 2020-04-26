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

import cats.effect.{ExitCase, IO}
import cats.effect.IO.Trace
import cats.effect.tracing.{FiberTracing, TraceElement}

private[effect] object IOTracing {

  def apply[A](source: IO[A]): IO[A] =
    IOBracket(enable)(_ => source)(disable)

  private def enable: IO[Boolean] =
    IO.delay {
      val os = FiberTracing.get()
      FiberTracing.set(true)
      os
    }

  // TODO: This finalizer doesn't actually work in the
  // case of cancellation because it is invoked by the thread
  // that initiated cancellation.
  // This leaves the thread the cancelled fiber was bound to in
  // an invalid state, so instead we have to reset the status when we
  // begin interpreting a new IO.
  private def disable(oldStatus: Boolean, exitCase: ExitCase[Throwable]): IO[Unit] =
    IO.delay {
      val isSameFiber = exitCase match {
        case ExitCase.Completed => true
        case ExitCase.Error(_) => true
        case _ => false
      }

      if (isSameFiber)
        FiberTracing.set(oldStatus)

      ()
    }

  def check[A](source: IO[A]): IO[A] = {
    if (FiberTracing.get()) {
      // The userspace method invocation is at least two frames away
      // TODO: filtering here?
      val stackTrace = new Throwable().getStackTrace.toList.map(TraceElement.fromStackTraceElement)
      Trace(source, stackTrace)
    } else {
      source
    }
  }

}
