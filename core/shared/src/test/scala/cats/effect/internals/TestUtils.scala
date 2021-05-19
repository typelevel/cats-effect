/*
 * Copyright (c) 2017-2021 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets

/**
 * INTERNAL API — test utilities.
 */
trait TestUtils {

  /**
   * Catches `System.err` output, for testing purposes.
   */
  def catchSystemErr(thunk: => Unit): String = {
    val outStream = new ByteArrayOutputStream()
    catchSystemErrInto(outStream)(thunk)
    new String(outStream.toByteArray, StandardCharsets.UTF_8)
  }

  /**
   * Catches `System.err` output into `outStream`, for testing purposes.
   */
  def catchSystemErrInto[T](outStream: OutputStream)(thunk: => T): T = synchronized {
    val oldErr = System.err
    val fakeErr = new PrintStream(outStream)
    System.setErr(fakeErr)
    try {
      thunk
    } finally {
      System.setErr(oldErr)
      fakeErr.close()
    }
  }
}
