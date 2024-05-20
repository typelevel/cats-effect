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

import org.typelevel.scalaccompat.annotation._

import scala.scalanative.libc.errno._
import scala.scalanative.libc.string._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private[std] trait SecureRandomCompanionPlatform {

  private[std] class JavaSecureRandom() extends java.util.Random(0L) {

    override def setSeed(x: Long): Unit = ()

    override def nextBytes(bytes: Array[Byte]): Unit = {
      val len = bytes.length
      var i = 0
      while (i < len) {
        val n = Math.min(256, len - i)
        if (sysrandom.getentropy(bytes.atUnsafe(i), n.toULong) < 0)
          throw new RuntimeException(fromCString(strerror(errno)))
        i += n
      }
    }

    override protected final def next(numBits: Int): Int = {
      if (numBits <= 0) {
        0 // special case because the formula on the last line is incorrect for numBits == 0
      } else {
        val bytes = stackalloc[CInt]()
        sysrandom.getentropy(bytes.asInstanceOf[Ptr[Byte]], sizeof[CInt])
        val rand32: Int = !bytes
        rand32 & (-1 >>> (32 - numBits)) // Clear the (32 - numBits) higher order bits
      }
    }

  }

}

@extern
@nowarn212
private[std] object sysrandom {
  def getentropy(buf: Ptr[Byte], buflen: CSize): Int = extern
}
