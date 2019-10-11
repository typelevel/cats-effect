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

package cats.effect
package internals

import java.util.concurrent.atomic.AtomicReference
import cats.effect.internals.TrampolineEC.immediate
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/**
 * A placeholder for a [[CancelToken]] that will be set at a later time,
 * the equivalent of a `Deferred[IO, CancelToken]`.
 *
 * Used in the implementation of `bracket`, see [[IOBracket]].
 */
final private[effect] class ForwardCancelable private () {
  import ForwardCancelable._

  private[this] val state = new AtomicReference[State](init)

  val cancel: CancelToken[IO] = {
    @tailrec def loop(conn: IOConnection, cb: Callback.T[Unit]): Unit =
      state.get() match {
        case current @ Empty(list) =>
          if (!state.compareAndSet(current, Empty(cb :: list)))
            loop(conn, cb)

        case Active(token) =>
          state.lazySet(finished) // GC purposes
          context.execute(new Runnable {
            def run() =
              IORunLoop.startCancelable(token, conn, cb)
          })
      }

    IO.Async(loop)
  }

  def complete(value: CancelToken[IO]): Unit =
    state.get() match {
      case current @ Active(_) =>
        value.unsafeRunAsyncAndForget()
        throw new IllegalStateException(current.toString)

      case current @ Empty(stack) =>
        if (current eq init) {
          // If `init`, then `cancel` was not triggered yet
          if (!state.compareAndSet(current, Active(value)))
            complete(value)
        } else {
          if (!state.compareAndSet(current, finished))
            complete(value)
          else
            execute(value, stack)
        }
    }
}

private[effect] object ForwardCancelable {

  /**
   * Builds reference.
   */
  def apply(): ForwardCancelable =
    new ForwardCancelable

  /**
   * Models the internal state of [[ForwardCancelable]]:
   *
   *  - on start, the state is [[Empty]] of `Nil`, aka [[init]]
   *  - on `cancel`, if no token was assigned yet, then the state will
   *    remain [[Empty]] with a non-nil `List[Callback]`
   *  - if a `CancelToken` is provided without `cancel` happening,
   *    then the state transitions to [[Active]] mode
   *  - on `cancel`, if the state was [[Active]], or if it was [[Empty]],
   *    regardless, the state transitions to `Active(IO.unit)`, aka [[finished]]
   */
  sealed abstract private class State

  final private case class Empty(stack: List[Callback.T[Unit]]) extends State
  final private case class Active(token: CancelToken[IO]) extends State

  private val init: State = Empty(Nil)
  private val finished: State = Active(IO.unit)
  private val context: ExecutionContext = immediate

  private def execute(token: CancelToken[IO], stack: List[Callback.T[Unit]]): Unit =
    context.execute(new Runnable {
      def run(): Unit =
        token.unsafeRunAsync { r =>
          for (cb <- stack)
            try {
              cb(r)
            } catch {
              // $COVERAGE-OFF$
              case NonFatal(e) => Logger.reportFailure(e)
              // $COVERAGE-ON$
            }
        }
    })
}
