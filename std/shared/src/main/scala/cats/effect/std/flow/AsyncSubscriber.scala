/*
 * Copyright 2020-2023 Typelevel
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

package cats.effect.std.flow

import java.util.Objects.requireNonNull
import java.util.concurrent.Flow.{Subscriber, Subscription}

/**
 * Implementation of a [[Subscriber]].
 *
 * This is used to obtain an effect from an upstream reactive-streams system.
 *
 * @see
 *   [[https://github.com/reactive-streams/reactive-streams-jvm#2-subscriber-code]]
 */
private[flow] final class AsyncSubscriber[F[_], A](
    cb: Either[Throwable, Option[A]] => Unit
) extends Subscriber[A] {
  private var subscription: Subscription = null

  /**
   * Receives a subscription from the upstream reactive-streams system.
   */
  override def onSubscribe(sub: Subscription): Unit = {
    requireNonNull(
      subscription,
      "The subscription provided to onSubscribe must not be null"
    )
    // When subscribed,
    // we store the subscription and request a single element.
    subscription = sub
    subscription.request(1L)
  }

  /**
   * Receives the next record from the upstream reactive-streams system.
   */
  override def onNext(a: A): Unit = {
    requireNonNull(
      a,
      "The element provided to onNext must not be null"
    )
    // When the element is received,
    // we attempt to complete the callback with it and then cancel the upstream system.
    cb(Right(Some(a)))
    subscription.cancel()
  }

  /**
   * Called by the upstream reactive-streams system when it fails.
   */
  override def onError(ex: Throwable): Unit = {
    requireNonNull(
      ex,
      "The throwable provided to onError must not be null"
    )
    // When an error is received,
    // we attempt to complete the callback with it and then cancel the upstream system.
    cb(Left(ex))
    subscription.cancel()
  }

  /**
   * Called by the upstream reactive-streams system when it has finished sending records.
   */
  override def onComplete(): Unit = {
    // When a completion signal is received,
    // we attempt to complete the callback with a None.
    cb(Right(None))
  }
}
