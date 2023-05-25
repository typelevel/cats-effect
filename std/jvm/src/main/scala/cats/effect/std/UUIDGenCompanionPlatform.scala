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

import cats.effect.kernel.Sync
import cats.syntax.all._

import java.util.UUID

private[std] trait UUIDGenCompanionPlatform {
  implicit def fromSync[F[_]](implicit ev: Sync[F]): UUIDGen[F] = {
    new UUIDGen[F] {
      override final val randomUUID: F[UUID] =
        for {
          jsr <- SecureRandom.javaSecuritySecureRandom[F]
          nBytes <- jsr.nextBytes(16)
          bArray <- Sync[F].delay(setUUIDVersion(nBytes))
          uuid <- Sync[F].delay(UUIDBuilder(bArray))
        } yield uuid

      private def setUUIDVersion(randomBytes: Array[Byte]) = {
        randomBytes(6) = (randomBytes(6) & 0x0f.toByte).toByte /* clear version */
        randomBytes(6) = (randomBytes(6) | 0x40.toByte).toByte /* set to version 4 */
        randomBytes(8) = (randomBytes(8) & 0x3f.toByte).toByte /* clear variant */
        randomBytes(8) = (randomBytes(8) | 0x80.toByte).toByte
        randomBytes
      }

      private def UUIDBuilder(data: Array[Byte]) = {
        var msb = 0
        var lsb = 0
        assert(data.length == 16, "data must be 16 bytes in length")
        for (i <- 0 until 8) {
          msb = (msb << 8) | (data(i) & 0xff)
        }
        for (i <- 8 until 16) {
          lsb = (lsb << 8) | (data(i) & 0xff)
        }
        val mostSigBits = msb
        val leastSigBits = lsb
        new UUID(mostSigBits, leastSigBits)
      }
    }
  }

}
