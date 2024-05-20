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

package cats.effect

import cats.effect.std.Console

import java.time.Instant

private[effect] abstract class IOCompanionPlatform { this: IO.type =>

  private[this] val TypeDelay = Sync.Type.Delay

  def blocking[A](thunk: => A): IO[A] =
    // do our best to mitigate blocking
    IO.cede *> apply(thunk).guarantee(IO.cede)

  private[effect] def interruptible[A](many: Boolean, thunk: => A): IO[A] = {
    val _ = many
    blocking(thunk)
  }

  def interruptible[A](thunk: => A): IO[A] = interruptible(false, thunk)

  def interruptibleMany[A](thunk: => A): IO[A] = interruptible(true, thunk)

  def suspend[A](hint: Sync.Type)(thunk: => A): IO[A] =
    if (hint eq TypeDelay)
      apply(thunk)
    else
      blocking(thunk)

  def realTimeInstant: IO[Instant] = asyncForIO.realTimeInstant

  /**
   * Reads a line as a string from the standard input using the platform's default charset, as
   * per `java.nio.charset.Charset.defaultCharset()`.
   *
   * The effect can raise a `java.io.EOFException` if no input has been consumed before the EOF
   * is observed. This should never happen with the standard input, unless it has been replaced
   * with a finite `java.io.InputStream` through `java.lang.System#setIn` or similar.
   *
   * @see
   *   `cats.effect.std.Console#readLineWithCharset` for reading using a custom
   *   `java.nio.charset.Charset`
   *
   * @return
   *   an IO effect that describes reading the user's input from the standard input as a string
   */
  def readLine: IO[String] =
    Console[IO].readLine

}
