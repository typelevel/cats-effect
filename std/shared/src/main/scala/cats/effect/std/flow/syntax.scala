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

import cats.effect.kernel.Async

import java.util.concurrent.Flow.Publisher

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

  final class FromPublisherPartiallyApplied[F[_]](private val dummy: Boolean) extends AnyVal {
    def apply[A](
        publisher: Publisher[A]
    )(
        implicit F: Async[F]
    ): F[Option[A]] =
      fromPublisher[F, A] { subscriber => F.delay(publisher.subscribe(subscriber)) }
  }
}
