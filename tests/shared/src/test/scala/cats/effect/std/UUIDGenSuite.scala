/*
 * Copyright 2020-2025 Typelevel
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
package std

import scala.concurrent.duration._
import scala.util.{Random => SRandom}

class UUIDGenSuite extends BaseSuite {

  real("securely generate UUIDs") {
    for {
      left <- UUIDGen.randomUUID[IO]
      right <- UUIDGen.randomUUID[IO]
    } yield assert(left != right)
  }

  real("use the correct variant and version") {
    for {
      uuid <- UUIDGen.randomUUID[IO]
    } yield {
      assertEquals(uuid.variant, 2)
      assertEquals(uuid.version, 4)
    }
  }

  real("securely generate UUIDv7s") {
    for {
      left <- UUIDGenV7[IO].randomUUID
      right <- UUIDGenV7[IO].randomUUID
    } yield assert(left != right)
  }

  real("use the correct variant and version for UUIDv7") {
    for {
      uuid <- UUIDGenV7[IO].randomUUID
    } yield {
      assertEquals(uuid.variant, 2)
      assertEquals(uuid.version, 7)
    }
  }

  real("embed the unix epoch millis in a UUIDv7") {
    val epochMillis = 1729778400123L
    for {
      uuid <- fixedClockUUIDGenV7(epochMillis).randomUUID
    } yield assertEquals(uuid.getMostSignificantBits >>> 16, epochMillis)
  }

  real("order UUIDv7s generated in different milliseconds") {
    for {
      first <- fixedClockUUIDGenV7(1729778400123L).randomUUID
      second <- fixedClockUUIDGenV7(1729778400124L).randomUUID
    } yield assert(
      java
        .lang
        .Long
        .compareUnsigned(first.getMostSignificantBits, second.getMostSignificantBits) < 0)
  }

  real("IO.randomUUIDv7 generates a UUIDv7") {
    for {
      uuid <- IO.randomUUIDv7
    } yield {
      assertEquals(uuid.variant, 2)
      assertEquals(uuid.version, 7)
    }
  }

  real("generate the same UUIDv7 for the same clock and random bytes") {
    for {
      left <- fixedClockUUIDGenV7(1729778400123L).randomUUID
      right <- fixedClockUUIDGenV7(1729778400123L).randomUUID
    } yield assertEquals(left, right)
  }

  real("place the random bytes in rand_a and rand_b of a UUIDv7") {
    val epochMillis = 1729778400123L
    val bytes = new Array[Byte](16)
    new SRandom(seed).nextBytes(bytes)

    val randA = ((bytes(6) & 0x0fL) << 8) | (bytes(7) & 0xffL)
    val randB = bytes.drop(8).foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xffL)) &
      0x3fffffffffffffffL

    for {
      uuid <- fixedClockUUIDGenV7(epochMillis).randomUUID
    } yield {
      assertEquals(uuid.getMostSignificantBits, (epochMillis << 16) | 0x7000L | randA)
      assertEquals(uuid.getLeastSignificantBits, 0x8000000000000000L | randB)
    }
  }

  private val seed = 42L
  private def fixedClockUUIDGenV7(epochMillis: Long): UUIDGen[IO] = {
    val fixedClock = new Clock[IO] {
      def applicative = IO.asyncForIO
      def monotonic = IO.pure(epochMillis.millis)
      def realTime = IO.pure(epochMillis.millis)
    }
    UUIDGenV7[IO](fixedSecureRandom(seed), fixedClock)
  }

  // Re-seeds on every call so each call to nextBytes returns the same bytes
  private def fixedSecureRandom(seed: Long): SecureRandom[IO] =
    new Random.ScalaRandom[IO](IO(new SRandom(seed))) with SecureRandom[IO] {}

}
