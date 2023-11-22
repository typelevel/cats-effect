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

package cats.effect.std

import cats.effect.kernel.Async
import cats.syntax.all._

import java.util.concurrent.Flow.Subscriber

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
   *   The publisher will not receive a subscriber until the effect is run.
   *
   * @see
   *   the `toEffect` extension method added to [[Publisher]].
   *
   * @param publisher
   *   The [[Publisher]] to consume.
   */
  def fromPublisher[F[_]]: syntax.FromPublisherPartiallyApplied[F] =
    new syntax.FromPublisherPartiallyApplied(dummy = true)
}
