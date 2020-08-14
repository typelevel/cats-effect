/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

import java.time.Instant

import cats.Functor
import cats.effect.Clock

import scala.concurrent.duration.MILLISECONDS

private[effect] trait ClockPlatform {
  implicit class JvmClockOps[F[_]](val self: Clock[F]) {

    /**
     * Creates a `java.time.Instant` derived from the clock's `realTime` in milliseconds
     * for any `F` that has `Functor` defined.
     */
    def instantNow(implicit F: Functor[F]): F[Instant] =
      F.map(self.realTime(MILLISECONDS))(Instant.ofEpochMilli)
  }
}
