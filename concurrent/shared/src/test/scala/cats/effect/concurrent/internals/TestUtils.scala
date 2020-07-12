/*
 * Copyright 2020 Typelevel
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

import scala.util.control.NonFatal

/**
 * INTERNAL API — test utilities.
 */
trait TestUtils {

  /**
   * Silences `System.err`, only printing the output in case exceptions are
   * thrown by the executed `thunk`.
   */
  def silenceSystemErr[A](thunk: => A): A = synchronized {
    // Silencing System.err
    val oldErr = System.err
    val outStream = new ByteArrayOutputStream()
    val fakeErr = new PrintStream(outStream)
    System.setErr(fakeErr)
    try {
      val result = thunk
      System.setErr(oldErr)
      result
    } catch {
      case NonFatal(e) =>
        System.setErr(oldErr)
        // In case of errors, print whatever was caught
        fakeErr.close()
        val out = new String(outStream.toByteArray, StandardCharsets.UTF_8)
        if (out.nonEmpty) oldErr.println(out)
        throw e
    }
  }

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
