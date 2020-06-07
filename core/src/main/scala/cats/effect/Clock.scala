/*
 * Copyright 2020 Typelevel
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

package cats.effect

import cats.Applicative

import scala.concurrent.duration.FiniteDuration

import java.time.Instant

trait Clock[F[_]] extends Applicative[F] {

  // (monotonic, monotonic).mapN(_ <= _)
  def monotonic: F[FiniteDuration]

  // lawless (unfortunately), but meant to represent current (when sequenced) system time
  def realTime: F[Instant]
}

object Clock {
  def apply[F[_]](implicit F: Clock[F]): F.type = F
}
