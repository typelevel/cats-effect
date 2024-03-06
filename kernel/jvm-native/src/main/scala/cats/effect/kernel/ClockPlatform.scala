/*
 * Copyright 2020-2024 Typelevel
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

package cats.effect.kernel

import java.time.Instant

private[effect] trait ClockPlatform[F[_]] extends Serializable { self: Clock[F] =>
  def realTimeInstant: F[Instant] = {
    self.applicative.map(self.realTime)(d => Instant.EPOCH.plusNanos(d.toNanos))
  }
}
