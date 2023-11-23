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

package cats.effect.interop
package flow

import cats.effect.IO
import cats.effect.kernel.{Async, Resource}
import cats.effect.unsafe.IORuntime

import java.util.concurrent.Flow.{Publisher, Subscriber}

object syntax {
  implicit final class PublisherOps[A](private val publisher: Publisher[A]) extends AnyVal {

    /**
     * Creates an effect from this [[Publisher]].
     *
     * This effect will request a single item from the [[Publisher]] and then cancel it. The
     * return value is an [[Option]] because the [[Publisher]] may complete without providing a
     * single value.
     *
     * @example
     *   {{{
     *   import cats.effect.IO
     *   import java.util.concurrent.Flow.Publisher
     *
     *   def getThirdPartyPublisher(): Publisher[Int] = ???
     *
     *   // Interop with the third party library.
     *   IO.delay(getThirdPartyPublisher()).flatMap { publisher =>
     *     publisher.toEffect[IO]
     *   }
     *   res0: IO[Int] = IO(..)
     *   }}}
     *
     * @note
     *   The [[Publisher]] will not receive a [[Subscriber]] until the effect is run.
     */
    def toEffect[F[_]](implicit F: Async[F]): F[Option[A]] =
      fromPublisher[F](publisher)
  }

  implicit final class FOps[F[_], A](private val fa: F[A]) extends AnyVal {

    /**
     * Creates a [[Publisher]] from this effect.
     *
     * The effect is only ran when elements are requested. Closing the [[Resource]] means
     * gracefully shutting down all active subscriptions. Thus, no more elements will be
     * published.
     *
     * @note
     *   The [[Publisher]] can be reused for multiple [[Subscribers]], each [[Subscription]]
     *   will re-run the effect.
     *
     * @see
     *   [[subscribe]] for a simpler version that only requires a [[Subscriber]].
     */
    def toPublisher(implicit F: Async[F]): Resource[F, Publisher[A]] =
      flow.toPublisher(fa)

    /**
     * Allows subscribing a [[Subscriber]] to this effect.
     *
     * The returned program will run this effect, then send the result to the [[Subscriber]],
     * and finally complete the subscription. Cancelling this program will gracefully cancel the
     * subscription.
     *
     * @param subscriber
     *   the [[Subscriber]] that will receive the result of this effect.
     */
    def subscribeEffect(
        subscriber: Subscriber[_ >: A]
    )(
        implicit F: Async[F]
    ): F[Unit] =
      flow.subscribeEffect(fa, subscriber)
  }

  implicit final class IOOps[A](private val ioa: IO[A]) extends AnyVal {

    /**
     * Creates a [[Publisher]] from this [[IO]].
     *
     * The [[IO]] is only ran when elements are requested.
     *
     * @note
     *   The [[Publisher]] can be reused for multiple [[Subscribers]], each [[Subscription]]
     *   will re-run the [[IO]].
     *
     * @see
     *   [[toPublisher]] for a safe version that returns a [[Resource]].
     */
    def unsafeToPublisher()(implicit runtime: IORuntime): Publisher[A] =
      flow.unsafeToPublisher(ioa)
  }

  final class FromPublisherPartiallyApplied[F[_]](private val dummy: Boolean) extends AnyVal {
    def apply[A](
        publisher: Publisher[A]
    )(
        implicit F: Async[F]
    ): F[Option[A]] =
      fromPublisher[F, A] { subscriber => F.delay(publisher.subscribe(subscriber)) }
  }
}
