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

import java.util.concurrent.atomic.AtomicReference
import cats.effect.IO
import cats.effect.internals.Cancelable.Dummy
import scala.annotation.tailrec

/**
 * INTERNAL API — a forward reference for a cancelable.
 *
 * Implementation inspired by Monix's `SingleAssignCancelable`.
 */
private[effect] final class ForwardCancelable private (plusOne: () => Unit)
  extends (() => Unit) {

  import ForwardCancelable._
  private[this] val state = new AtomicReference(IsEmpty : () => Unit)

  @tailrec def apply(): Unit = {
    state.get match {
      case IsCanceled | IsEmptyCanceled => ()
      case IsEmpty =>
        if (!state.compareAndSet(IsEmpty, IsEmptyCanceled))
          apply() // retry
        else if (plusOne ne null)
          plusOne()
      case current =>
        if (!state.compareAndSet(current, IsCanceled))
          apply() // retry
        else {
          try current() finally
            if (plusOne ne null) plusOne()
        }
    }
  }

  def :=(cancelable: () => Unit): Unit =
    state.getAndSet(cancelable) match {
      case IsEmptyCanceled => cancelable()
      case IsEmpty => ()
      case _ =>
        throw new IllegalStateException("ForwardCancelable already assigned")
    }

  def :=(task: IO[Unit]): Unit =
    this.:= { () => task.unsafeRunAsync(Callback.report) }
}

private[effect] object ForwardCancelable {
  /**
   * Builder for a `ForwardCancelable`.
   */
  def apply(): ForwardCancelable =
    new ForwardCancelable(null)

  /**
   * Builder for a `ForwardCancelable` that also cancels
   * a second reference when cancelled.
   */
  def plusOne(ref: () => Unit): ForwardCancelable =
    new ForwardCancelable(ref)

  private object IsEmpty extends Dummy
  private object IsCanceled extends Dummy
  private object IsEmptyCanceled extends Dummy
}