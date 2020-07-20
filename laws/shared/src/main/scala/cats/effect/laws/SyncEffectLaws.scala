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
package laws

import cats.effect.kernel.SyncEffect

trait SyncEffectLaws[F[_]] extends SyncLaws[F] {
  implicit val F: SyncEffect[F]

  def roundTrip[A](fa: F[A]) =
    F.to[F](fa) <-> fa
}

object SyncEffectLaws {
  def apply[F[_]](implicit F0: SyncEffect[F]): SyncEffectLaws[F] =
    new SyncEffectLaws[F] { val F = F0 }
}
