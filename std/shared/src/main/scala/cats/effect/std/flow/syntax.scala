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
  final class FromPublisherPartiallyApplied[F[_]](private val dummy: Boolean) extends AnyVal {
    def apply[A](
        publisher: Publisher[A]
    )(
        implicit F: Async[F]
    ): F[Option[A]] =
      fromPublisher[F, A] { subscriber => F.delay(publisher.subscribe(subscriber)) }
  }
}
