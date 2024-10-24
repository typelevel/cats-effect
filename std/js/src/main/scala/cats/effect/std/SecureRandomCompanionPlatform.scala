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

/*
 * scalajs-java-securerandom (https://github.com/scala-js/scala-js-java-securerandom)
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

import cats.Applicative
import cats.effect.kernel.Sync
import cats.effect.std.Random.ScalaRandom

import scala.scalajs.js
import scala.scalajs.js.typedarray._

private[std] trait SecureRandomCompanionPlatform {
  // The seed in java.util.Random will be unused, so set to 0L instead of having to generate one
  private[std] class JavaSecureRandom() extends java.util.Random(0L) {
    // Make sure to resolve the appropriate function no later than the first instantiation
    private val getRandomValuesFun = JavaSecureRandom.getRandomValuesFun

    /* setSeed has no effect. For cryptographically secure PRNGs, giving a seed
     * can only ever increase the entropy. It is never allowed to decrease it.
     * Given that we don't have access to an API to strengthen the entropy of the
     * underlying PRNG, it's fine to ignore it instead.
     *
     * Note that the doc of `SecureRandom` says that it will seed itself upon
     * first call to `nextBytes` or `next`, if it has not been seeded yet. This
     * suggests that an *initial* call to `setSeed` would make a `SecureRandom`
     * instance deterministic. Experimentally, this does not seem to be the case,
     * however, so we don't spend extra effort to make that happen.
     */
    override def setSeed(x: Long): Unit = ()

    override def nextBytes(bytes: Array[Byte]): Unit = {
      val len = bytes.length
      val buffer = new Int8Array(len)
      getRandomValuesFun(buffer)
      var i = 0
      while (i != len) {
        bytes(i) = buffer(i)
        i += 1
      }
    }

    override protected final def next(numBits: Int): Int = {
      if (numBits <= 0) {
        0 // special case because the formula on the last line is incorrect for numBits == 0
      } else {
        val buffer = new Int32Array(1)
        getRandomValuesFun(buffer)
        val rand32 = buffer(0)
        rand32 & (-1 >>> (32 - numBits)) // Clear the (32 - numBits) higher order bits
      }
    }
  }

  private[std] object JavaSecureRandom {
    private lazy val getRandomValuesFun: js.Function1[ArrayBufferView, Unit] = {
      if (js.typeOf(js.Dynamic.global.crypto) != "undefined" &&
        js.typeOf(js.Dynamic.global.crypto.getRandomValues) == "function") {
        { (buffer: ArrayBufferView) =>
          js.Dynamic.global.crypto.getRandomValues(buffer)
          ()
        }
      } else if (js.typeOf(js.Dynamic.global.require) == "function") {
        try {
          val crypto = js.Dynamic.global.require("crypto")
          if (js.typeOf(crypto.randomFillSync) == "function") {
            { (buffer: ArrayBufferView) =>
              crypto.randomFillSync(buffer)
              ()
            }
          } else {
            notSupported
          }
        } catch {
          case _: Throwable =>
            notSupported
        }
      } else {
        notSupported
      }
    }

    private def notSupported: Nothing = {
      throw new UnsupportedOperationException(
        "java.security.SecureRandom is not supported on this platform " +
          "because it provides neither `crypto.getRandomValues` nor " +
          "Node.js' \"crypto\" module."
      )
    }
  }

  def javaSecuritySecureRandom[F[_]: Sync]: F[SecureRandom[F]] =
    Sync[F].delay(unsafeJavaSecuritySecureRandom())

  private[effect] def unsafeJavaSecuritySecureRandom[F[_]: Sync](): SecureRandom[F] =
    new ScalaRandom[F](Applicative[F].pure(new JavaSecureRandom())) with SecureRandom[F] {}

}
