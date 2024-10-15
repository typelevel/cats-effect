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

package cats.effect.interop.flow

import cats.effect.kernel.{Async, Outcome}
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

import java.util.concurrent.CancellationException
import java.util.concurrent.Flow.{Subscription, Subscriber}
import java.util.concurrent.atomic.AtomicReference

/**
 * Implementation of a [[Subscription]].
 *
 * This is used by the [[AsyncPublisher]] to send the result of an effect to a downstream
 * reactive-streams system.
 *
 * @see
 *   [[https://github.com/reactive-streams/reactive-streams-jvm#3-subscription-code]]
 */
private[flow] final class AsyncSubscription[F[_], A] private (
    fa: F[A],
    subscriber: Subscriber[_ >: A],
    start: AtomicReference[() => Unit],
    canceled: AtomicReference[() => Unit]
)(
    implicit F: Async[F]
) extends Subscription {
  import AsyncSubscription.Sentinel

  // Ensure we are on a terminal state; i.e. call `cancel`, before signaling the subscriber.
  private def onError(ex: Throwable): Unit = {
    cancel()
    subscriber.onError(ex)
  }

  private def onComplete(): Unit = {
    cancel()
    subscriber.onComplete()
  }

  // This is a def rather than a val, because it is only used once.
  // And having fields increase the instantiation cost and delay garbage collection.
  def run: F[Unit] = {
    val cancellation = F.asyncCheckAttempt[Unit] { cb =>
      F.delay {
        // Check if we were already cancelled before calling run.
        if (!canceled.compareAndSet(Sentinel, () => cb.apply(Either.unit))) {
          Either.unit
        } else {
          Left(Some(F.unit))
        }
      }
    }

    val waitForRequest = F.async_[Unit] { cb => start.set(() => cb.apply(Either.unit)) }

    (waitForRequest >> fa)
      .race(cancellation)
      .guaranteeCase {
        case Outcome.Succeeded(result) =>
          result.flatMap {
            // The effect finished normally.
            case Left(a) =>
              F.delay {
                subscriber.onNext(a)
                onComplete()
              }

            case Right(()) =>
              // The effect was canceled by downstream.
              F.unit
          }

        case Outcome.Errored(ex) =>
          // The effect failed with an error.
          F.delay(onError(ex))

        case Outcome.Canceled() =>
          // The effect was canceled by upstream.
          F.delay(onError(ex = new CancellationException("AsyncSubscription.run was canceled")))
      }
      .void
  }

  override final def cancel(): Unit = {
    val cancelCB = canceled.getAndSet(null)
    if (cancelCB ne null) {
      cancelCB.apply()
    }
  }

  override final def request(n: Long): Unit = {
    // First, confirm we are not yet cancelled.
    if (canceled.get() ne null) {
      // Second, ensure we were requested a positive number of elements.
      if (n <= 0) {
        // Otherwise, we raise an error according to the spec.
        onError(ex = new IllegalArgumentException(s"Invalid number of elements [${n}]"))
      } else {
        // Then, we attempt to complete the start callback.
        val startCB = start.getAndSet(null)
        if (startCB ne null) {
          startCB.apply()
        }
      }
    }
  }
}

private[flow] object AsyncSubscription {
  private final val Sentinel = () => ()

  // Mostly for testing purposes.
  def apply[F[_], A](
      fa: F[A],
      subscriber: Subscriber[_ >: A]
  )(
      implicit F: Async[F]
  ): F[AsyncSubscription[F, A]] =
    F.delay {
      val start = new AtomicReference(Sentinel)
      val canceled = new AtomicReference(Sentinel)

      new AsyncSubscription(
        fa,
        subscriber,
        start,
        canceled
      )
    }

  def subscribe[F[_], A](
      fa: F[A],
      subscriber: Subscriber[_ >: A]
  )(
      implicit F: Async[F]
  ): F[Unit] =
    apply(fa, subscriber).flatMap { subscription =>
      F.delay(subscriber.onSubscribe(subscription)) >>
        subscription.run
    }
}
