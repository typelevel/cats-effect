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

package cats.effect.std

import cats.effect.kernel.Sync

import java.util.UUID

private[std] trait UUIDGenCompanionPlatform extends UUIDGenCompanionPlatformLowPriority

private[std] trait UUIDGenCompanionPlatformLowPriority {

  @deprecated(
    "Put an implicit `SecureRandom.javaSecuritySecureRandom` into scope to get a more efficient `UUIDGen`, or directly call `UUIDGen.fromSecureRandom`",
    "3.6.0"
  )
  implicit def fromSync[F[_]](implicit ev: Sync[F]): UUIDGen[F] = new UUIDGen[F] {
    override final val randomUUID: F[UUID] =
      ev.blocking(UUID.randomUUID())
  }

}
