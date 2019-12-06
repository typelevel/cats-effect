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

package cats
package effect
package laws

import cats.laws._

trait SyncEffectLaws[F[_]] extends SyncLaws[F] {
  implicit def F: SyncEffect[F]

  def toSyncIOAndBackIsIdentity[A](fa: F[A]) =
    SyncEffect[SyncIO].runSync[F, A](F.runSync[SyncIO, A](fa)) <-> fa
}

object SyncEffectLaws {
  def apply[F[_]](implicit F0: SyncEffect[F]): SyncEffectLaws[F] = new SyncEffectLaws[F] {
    val F = F0
  }
}
