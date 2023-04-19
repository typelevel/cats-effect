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

/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package cats.effect.std

import cats.effect.kernel.Sync

import java.util.UUID

private[std] trait UUIDGenCompanionPlatform {
  implicit def fromSync[F[_]](implicit ev: Sync[F]): UUIDGen[F] = new UUIDGen[F] {
    private val csprng = new SecureRandom.JavaSecureRandom()
    private val randomUUIDBuffer = new Array[Byte](16)
    override final val randomUUID: F[UUID] =
      ev.delay {
        val buffer = randomUUIDBuffer // local copy

        /* We use nextBytes() because that is the primitive of most secure RNGs,
         * and therefore it allows to perform a unique call to the underlying
         * secure RNG.
         */
        csprng.nextBytes(randomUUIDBuffer)

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
