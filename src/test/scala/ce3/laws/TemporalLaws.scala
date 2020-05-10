/*
 * Copyright 2020 Daniel Spiewak
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

package ce3
package laws

import cats.MonadError
import cats.implicits._
import cats.laws.MonadErrorLaws

import scala.concurrent.duration.FiniteDuration

trait TemporalLaws[F[_], E] extends ConcurrentLaws[F, E] {

  implicit val F: Temporal[F, E]

  def nowSleepSumIdentity(delta: FiniteDuration) =
    F.sleep(delta) >> F.now <~> F.now.map(delta +)
}

object TemporalLaws {
  def apply[F[_], E](implicit F0: Temporal[F, E]): TemporalLaws[F, E] =
    new TemporalLaws[F, E] { val F = F0 }
}
