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

import java.nio.charset.Charset

private[std] trait ConsolePlatform[F[_]] {

  @deprecated("Not implemented for Scala.js. On Node.js consider using fs2.io.stdin.", "3.4.0")
  def readLine: F[String] =
    readLineWithCharset(Charset.defaultCharset())

  @deprecated("Not implemented for Scala.js. On Node.js consider using fs2.io.stdin.", "3.4.0")
  def readLineWithCharset(charset: Charset): F[String]

}
