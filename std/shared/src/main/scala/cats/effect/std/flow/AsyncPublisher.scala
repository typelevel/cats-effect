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

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Dispatcher

import java.util.Objects.requireNonNull
import java.util.concurrent.Flow.{Publisher, Subscriber, Subscription}
import scala.util.control.NoStackTrace

private[flow] final class AsyncPublisher[F[_], A] private (
    fa: F[A],
    startDispatcher: Dispatcher[F]
)(
    implicit F: Async[F]
) extends Publisher[A] {
  override final def subscribe(subscriber: Subscriber[_ >: A]): Unit = {
    requireNonNull(
      subscriber,
      "The subscriber provided to subscribe must not be null"
    )
    try
      startDispatcher.unsafeRunAndForget(
        AsyncSubscription.subscribe(fa, subscriber)
      )
    catch {
      case _: IllegalStateException =>
        subscriber.onSubscribe(new Subscription {
          override def cancel(): Unit = ()
          override def request(x$1: Long): Unit = ()
        })
        subscriber.onError(AsyncPublisher.CanceledStreamPublisherException)
    }
  }
}

private[flow] object AsyncPublisher {
  def apply[F[_], A](
      fa: F[A]
  )(
      implicit F: Async[F]
  ): Resource[F, AsyncPublisher[F, A]] =
    Dispatcher.parallel[F](await = false).map { startDispatcher =>
      new AsyncPublisher(fa, startDispatcher)
    }

  private object CanceledStreamPublisherException
      extends IllegalStateException(
        "This StreamPublisher is not longer accepting subscribers"
      )
      with NoStackTrace
}
