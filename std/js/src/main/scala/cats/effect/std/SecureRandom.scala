/*
 * Copyright 2020-2022 Typelevel
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

import scala.scalajs.js

private final class SecureRandom extends java.util.Random {

  private val nextBytes: Int => js.typedarray.Int8Array =
    if (js.typeOf(js.Dynamic.global.crypto) != "undefined") // browsers
      { numBytes =>
        val bytes = new js.typedarray.Int8Array(numBytes)
        js.Dynamic.global.crypto.getRandomValues(bytes)
        bytes
      } else {
      val crypto = js.Dynamic.global.require("crypto")

      // Node.js
      { numBytes =>
        val bytes = crypto.randomBytes(numBytes).asInstanceOf[js.typedarray.Uint8Array]
        new js.typedarray.Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)

      }
    }

  override def nextBytes(bytes: Array[Byte]): Unit = {
    nextBytes(bytes.length).copyToArray(bytes)
    ()
  }

  override protected final def next(numBits: Int): Int = {
    val numBytes = (numBits + 7) / 8
    val b = new js.typedarray.Int8Array(nextBytes(numBytes).buffer)
    var next = 0

    var i = 0
    while (i < numBytes) {
      next = (next << 8) + (b(i) & 0xff)
      i += 1
    }

    next >>> (numBytes * 8 - numBits)
  }

}
