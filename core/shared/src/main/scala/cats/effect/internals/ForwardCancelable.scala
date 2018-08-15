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

import cats.effect.{CancelToken, IO}
import cats.effect.internals.TrampolineEC.immediate
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Promise}

/**
 * INTERNAL API — a forward reference for a cancelable.
 */
private[effect] final class ForwardCancelable private (plusOne: CancelToken[IO]) {
  import ForwardCancelable._
  import ForwardCancelable.State._

  private[this] val state = new AtomicReference(IsEmpty : State)

  val cancel: CancelToken[IO] = {
    @tailrec def loop(ref: Promise[Unit])(implicit ec: ExecutionContext): CancelToken[IO] = {
      state.get match {
        case IsCanceled =>
          IO.unit
        case IsEmptyCanceled(p) =>
          IOFromFuture(p.future)
        case IsEmpty =>
          val p = if (ref ne null) ref else Promise[Unit]()
          if (!state.compareAndSet(IsEmpty, IsEmptyCanceled(p)))
            loop(p) // retry
          else {
            val token = IOFromFuture(p.future)
            if (plusOne ne null)
              CancelUtils.cancelAll(token, plusOne)
            else
              token
          }
        case current @ Reference(token) =>
          if (!state.compareAndSet(current, IsCanceled))
            loop(ref) // retry
          else if (plusOne ne null)
            CancelUtils.cancelAll(token, plusOne)
          else
            token
      }
    }
    IO.suspend(loop(null)(immediate))
  }

  @tailrec def :=(token: CancelToken[IO]): Unit =
    state.get() match {
      case IsEmpty =>
        if (!state.compareAndSet(IsEmpty, Reference(token)))
          :=(token) // retry
      case current @ IsEmptyCanceled(p) =>
        if (!state.compareAndSet(current, IsCanceled))
          :=(token) // retry
        else
          token.unsafeRunAsync(Callback.promise(p))
      case _ =>
        throw new IllegalStateException("ForwardCancelable already assigned")
    }
}

private[effect] object ForwardCancelable {
  /**
   * Builder for a `ForwardCancelable`.
   */
  def apply(): ForwardCancelable =
    new ForwardCancelable(null)

  /**
   * Builder for a `ForwardCancelable` that also cancels
   * a second reference when canceled.
   */
  def plusOne(ref: CancelToken[IO]): ForwardCancelable =
    new ForwardCancelable(ref)

  /**
   * Models the state machine of our forward reference.
   */
  private sealed abstract class State

  private object State {
    /** Newly initialized `ForwardCancelable`. */
    object IsEmpty extends State

    /**
     * Represents a cancelled `ForwardCancelable` that had a `Reference`
     * to interrupt on cancellation. This is a final state.
     */
    object IsCanceled extends State

    /**
     * Represents a cancelled `ForwardCancelable` that was caught
     * in an `IsEmpty` state on interruption.
     *
     * When cancelling a `ForwardCancelable` we still have to
     * back-pressure on the pending cancelable reference, hence we
     * register a callback.
     */
    final case class IsEmptyCanceled(p: Promise[Unit])
      extends State

    /**
     * Non-cancelled state in which a token was assigned to our
     * forward reference, awaiting cancellation.
     */
    final case class Reference(token: CancelToken[IO])
      extends State
  }
}
