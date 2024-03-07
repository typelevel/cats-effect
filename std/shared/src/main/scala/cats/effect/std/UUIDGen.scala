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

  implicit def fromSecureRandom[F[_]: Functor: SecureRandom]: UUIDGen[F] =
    new UUIDGen[F] {
      override final val randomUUID: F[UUID] =
        SecureRandom[F].nextBytes(16).map(unsafeUUIDBuilder)

      private def unsafeUUIDBuilder(buffer: Array[Byte]): UUID = {
        @inline def intFromBuffer(i: Int): Int =
          (buffer(i) << 24) | ((buffer(i + 1) & 0xff) << 16) | ((buffer(
            i + 2) & 0xff) << 8) | (buffer(i + 3) & 0xff)

        val i1 = intFromBuffer(0)
        val i2 = (intFromBuffer(4) & ~0x0000f000) | 0x00004000
        val i3 = (intFromBuffer(8) & ~0xc0000000) | 0x80000000
        val i4 = intFromBuffer(12)
        val msb = (i1.toLong << 32) | (i2.toLong & 0xffffffffL)
        val lsb = (i3.toLong << 32) | (i4.toLong & 0xffffffffL)
        new UUID(msb, lsb)
      }
    }
}
