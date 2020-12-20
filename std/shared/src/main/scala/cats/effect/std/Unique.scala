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

package cats.effect.std

import cats.{Applicative, Defer, Hash}

/**
 * Unique is a unique identifier.
 *
 * The default constructor takes [[Defer]] and an [[Applicative]],
 * meaning that it can be safely used in any effect context which
 * has a lazy suspension semantic (including [[Spawn]] and [[Sync]]).
 *
 * The `toString` includes the object hash code as a hex string.
 * Note: the hash code is not unique, so it is possible for two
 * `Unique` instances to be different yet have the same string
 * representation.
 *
 * Alumnus of the Davenverse
 */
final class Unique private extends Serializable {
  override def toString: String = s"Unique(${hashCode.toHexString})"
}

object Unique {

  def apply[F[_]: Applicative: Defer]: F[Unique] =
    Defer[F].defer(Applicative[F].pure(new Unique()))

  implicit val uniqueInstances: Hash[Unique] =
    Hash.fromUniversalHashCode[Unique]
}
