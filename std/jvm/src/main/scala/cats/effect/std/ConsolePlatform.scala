/*
 * Copyright 2020-2021 Typelevel
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

import java.nio.charset.Charset

private[std] trait ConsolePlatform[F[_]] {

  /**
   * Reads a line as a string from the standard input using the platform's default charset, as
   * per `java.nio.charset.Charset.defaultCharset()`.
   *
   * The effect can raise a `java.io.EOFException` if no input has been consumed before the EOF
   * is observed. This should never happen with the standard input, unless it has been replaced
   * with a finite `java.io.InputStream` through `java.lang.System#setIn` or similar.
   *
   * @return
   *   an effect that describes reading the user's input from the standard input as a string
   */
  def readLine: F[String] =
    readLineWithCharset(Charset.defaultCharset())

  /**
   * Reads a line as a string from the standard input using the provided charset.
   *
   * The effect can raise a `java.io.EOFException` if no input has been consumed before the EOF
   * is observed. This should never happen with the standard input, unless it has been replaced
   * with a finite `java.io.InputStream` through `java.lang.System#setIn` or similar.
   *
   * @param charset
   *   the `java.nio.charset.Charset` to be used when decoding the input stream
   * @return
   *   an effect that describes reading the user's input from the standard input as a string
   */
  def readLineWithCharset(charset: Charset): F[String]

}
