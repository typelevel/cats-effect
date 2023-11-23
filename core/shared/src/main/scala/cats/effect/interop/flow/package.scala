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

import cats.effect.IO
import cats.effect.kernel.{Async, Resource}
import cats.effect.unsafe.IORuntime
import cats.syntax.all._

import java.util.concurrent.Flow.{Publisher, Subscriber}

/**
 * Implementation of the reactive-streams protocol for cats-effect; based on Java Flow.
 *
 * @example
 *   {{{
 *   import cats.effect.{IO, Resource}
 *   import std.flow.syntax._ scala>
 *   import java.util.concurrent.Flow.Publisher
 *   import cats.effect.unsafe.implicits.global
 *
 *   val upstream: IO[Int] = IO.pure(1)
 *   val publisher: Resource[IO, Publisher[Int]] = upstream.toPublisher
 *   val downstream: IO[Int] = publisher.use(_.toEffect[IO])
 *   downstream.unsafeRunSync()
 *   // res0: Int = 1
 *   }}}
 *
 * @see
 *   [[java.util.concurrent.Flow]]
 */
package object flow {

  /**
   * Creates an effect from a `subscribe` function; analogous to a `Publisher`, but effectual.
   *
   * This effect will request a single item from the [[Publisher]] and then cancel it. The
   * return value is an [[Option]] because the [[Publisher]] may complete without providing a
   * single value.
   *
   * This function is useful when you actually need to provide a subscriber to a third-party.
   *
   * @example
   *   {{{
   *   import cats.effect.IO
   *   import java.util.concurrent.Flow.{Publisher, Subscriber}
   *
   *   def thirdPartyLibrary(subscriber: Subscriber[Int]): Unit = {
   *     val somePublisher: Publisher[Int] = ???
   *     somePublisher.subscribe(subscriber)
   *   }
   *
   *   // Interop with the third party library.
   *   cats.effect.std.flow.fromPublisher[IO, Int] { subscriber =>
   *     IO.println("Subscribing!") >>
   *     IO.delay(thirdPartyLibrary(subscriber)) >>
   *     IO.println("Subscribed!")
   *   }
   *   res0: IO[Int] = IO(..)
   *   }}}
   *
   * @note
   *   The subscribe function will not be executed until the effect is run.
   *
   * @see
   *   the overload that only requires a [[Publisher]].
   *
   * @param subscribe
   *   The effectual function that will be used to initiate the consumption process, it receives
   *   a [[Subscriber]] that should be used to subscribe to a [[Publisher]]. The `subscribe`
   *   operation must be called exactly once.
   */
  def fromPublisher[F[_], A](
      subscribe: Subscriber[A] => F[Unit]
  )(
      implicit F: Async[F]
  ): F[Option[A]] =
    F.async { cb =>
      AsyncSubscriber(cb).flatMap { subscriber =>
        subscribe(subscriber).as(
          Some(subscriber.cancel)
        )
      }
    }

  /**
   * Creates an effect from a [[Publisher]].
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
   *     cats.effect.std.flow.fromPublisher[IO](publisher)
   *   }
   *   res0: IO[Int] = IO(..)
   *   }}}
   *
   * @note
   *   The [[Publisher]] will not receive a [[Subscriber]] until the effect is run.
   *
   * @see
   *   the `toEffect` extension method added to [[Publisher]].
   *
   * @param publisher
   *   The [[Publisher]] to consume.
   */
  def fromPublisher[F[_]]: syntax.FromPublisherPartiallyApplied[F] =
    new syntax.FromPublisherPartiallyApplied(dummy = true)

  /**
   * Creates a [[Publisher]] from an effect.
   *
   * The effect is only ran when elements are requested. Closing the [[Resource]] means
   * gracefully shutting down all active subscriptions. Thus, no more elements will be
   * published.
   *
   * @note
   *   This [[Publisher]] can be reused for multiple [[Subscribers]], each [[Subscription]] will
   *   re-run the effect.
   *
   * @see
   *   [[unsafeToPublisher]] for an unsafe version that returns a plain [[Publisher]].
   *
   * @see
   *   [[subscribeEffect]] for a simpler version that only requires a [[Subscriber]].
   *
   * @param fa
   *   The effect to transform.
   */
  def toPublisher[F[_], A](
      fa: F[A]
  )(
      implicit F: Async[F]
  ): Resource[F, Publisher[A]] =
    AsyncPublisher(fa)

  /**
   * Creates a [[Publisher]] from an [[IO]].
   *
   * The [[IO]] is only ran when elements are requested.
   *
   * @note
   *   This [[Publisher]] can be reused for multiple [[Subscribers]], each [[Subscription]] will
   *   re-run the [[IO]].
   *
   * @see
   *   [[toPublisher]] for a safe version that returns a [[Resource]].
   *
   * @param ioa
   *   The [[IO]] to transform.
   */
  def unsafeToPublisher[A](
      ioa: IO[A]
  )(
      implicit runtime: IORuntime
  ): Publisher[A] =
    AsyncPublisher.unsafe(ioa)

  /**
   * Allows subscribing a [[Subscriber]] to an effect.
   *
   * The returned program will run the passed effect, then send the result to the
   * [[Subscriber]], and finally complete the subscription. Cancelling this program will
   * gracefully cancel the subscription.
   *
   * @param fa
   *   the effect that will be consumed by the subscriber.
   * @param subscriber
   *   the [[Subscriber]] that will receive the result of the effect.
   */
  def subscribeEffect[F[_], A](
      fa: F[A],
      subscriber: Subscriber[_ >: A]
  )(
      implicit F: Async[F]
  ): F[Unit] =
    AsyncSubscription.subscribe(fa, subscriber)
}
