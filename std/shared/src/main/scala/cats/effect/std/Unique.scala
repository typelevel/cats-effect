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

package cats
package effect
package std

import cats.Hash
import cats.effect.Sync
import cats.syntax.all._

final class Unique private extends Serializable {
  override def toString: String = s"Unique(${hashCode.toHexString})"
}
object Unique {
  def apply[F[_]: Concurrent]: F[Unique] = Concurrent[F].unit.as(new Unique)

  implicit val uniqueInstances: Hash[Unique] =
    Hash.fromUniversalHashCode[Unique]
}
