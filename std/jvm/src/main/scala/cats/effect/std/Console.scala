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

import cats.{~>, Show}
import cats.effect.kernel.Sync

import java.nio.charset.Charset

/**
 * Effect type agnostic `Console` with common methods to write to and read from the standard
 * console. Suited only for extremely simple console input and output.
 *
 * @example
 *   {{{
 * import cats.effect.std.Console
 * import cats.effect.kernel.Sync
 * import cats.syntax.all._
 *
 * implicit val console = Console.sync[F]
 *
 * def myProgram[F[_]: Console]: F[Unit] =
 *   for {
 *     _ <- Console[F].println("Please enter your name: ")
 *     n <- Console[F].readLine
 *     _ <- if (n.nonEmpty) Console[F].println("Hello, " + n) else Console[F].errorln("Name is empty!")
 *   } yield ()
 *   }}}
 */
trait Console[F[_]] extends ConsoleCrossPlatform[F] {

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

  // redeclarations for bincompat

  def print[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit]

  def println[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit]

  def error[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit]

  def errorln[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit]

  def printStackTrace(t: Throwable): F[Unit] =
    Console.printStackTrace(this)(t)

  def mapK[G[_]](f: F ~> G): Console[G] = Console.mapK(this)(f)

}

object Console extends ConsoleCompanionCrossPlatform {

  /**
   * Constructs a `Console` instance for `F` data types that are [[cats.effect.kernel.Sync]].
   */
  def make[F[_]](implicit F: Sync[F]): Console[F] =
    new SyncConsole[F]

  private[std] abstract class MapKConsole[F[_], G[_]](self: Console[F], f: F ~> G)
      extends Console[G] {
    def readLineWithCharset(charset: Charset): G[String] =
      f(self.readLineWithCharset(charset))
  }

}
