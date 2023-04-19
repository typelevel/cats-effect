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

package cats.effect.std

import cats.Functor
import cats.implicits._

import java.util.UUID

/**
 * A purely functional UUID Generator
 */
trait UUIDGen[F[_]] {

  /**
   * Generates a UUID in a pseudorandom manner.
   * @return
   *   randomly generated UUID
   */
  def randomUUID: F[UUID]
}

object UUIDGen extends UUIDGenCompanionPlatform {
  def apply[F[_]](implicit ev: UUIDGen[F]): UUIDGen[F] = ev

  def randomUUID[F[_]: UUIDGen]: F[UUID] = UUIDGen[F].randomUUID
  def randomString[F[_]: UUIDGen: Functor]: F[String] = randomUUID.map(_.toString)
}
