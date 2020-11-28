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

import cats.effect.{Clock, Sync}

import scala.concurrent.duration.{NANOSECONDS, SECONDS, TimeUnit}

abstract private[effect] class DefaultClock[F[_]](implicit F: Sync[F]) extends Clock[F] {
  def realTime(unit: TimeUnit): F[Long] = F.delay {
    val now = Instant.now()
    unit.convert(now.getEpochSecond, SECONDS) + unit.convert(now.getNano.toLong, NANOSECONDS)
  }
}
