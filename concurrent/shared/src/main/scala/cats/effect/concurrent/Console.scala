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

/*
 * This is an adapted version of the code originally found in https://github.com/profunktor/console4cats,
 * by Gabriel Volpe.
 */

package cats.effect.concurrent

import cats.Show
import cats.effect.kernel.Sync
import cats.syntax.show._

import scala.annotation.tailrec

import java.lang.{StringBuilder => JStringBuilder}
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{Charset, CodingErrorAction, MalformedInputException}

/**
 * Effect type agnostic `Console` with common methods to write to and read from the standard console. Suited only
 * for extremely simple console output.
 *
 * @example {{{
 *   import cats.effect.concurrent.Console
 *   import cats.effect.kernel.Sync
 *   import cats.syntax.all._
 *
 *   def myProgram[F[_]: Sync]: F[Unit] =
 *     for {
 *       _ <- Console[F].println("Please enter your name: ")
 *       n <- Console[F].readLine
 *       _ <- if (n.nonEmpty) Console[F].println(s"Hello $n!")
 *            else Console[F].errorln("Name is empty!")
 *     } yield ()
 * }}}
 */
final class Console[F[_]] private (val F: Sync[F]) extends AnyVal {

  /**
   * Reads a line as a string from the standard input by using the platform's default charset, as per
   * `java.nio.charset.Charset.defaultCharset()`.
   *
   * @return an effect that describes reading the user's input from the standard input as a string
   */
  def readLine: F[String] =
    readLineWithCharset(Charset.defaultCharset())

  /**
   * Reads a line as a string from the standard input by using the provided charset.
   *
   * @param charset the `java.nio.charset.Charset` to be used when decoding the input stream
   * @return an effect that describes reading the user's input from the standard input as a string
   */
  def readLineWithCharset(charset: Charset): F[String] =
    F.interruptible(false) {
      val in = System.in
      val decoder = charset
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
      val bytes = ByteBuffer.allocate(16)
      val builder = new JStringBuilder()

      def decodeNext(): CharBuffer = {
        bytes.clear()
        decodeNextLoop()
      }

      @tailrec
      def decodeNextLoop(): CharBuffer = {
        val b = in.read()
        if (b == -1) {
          return null
        }
        bytes.put(b.toByte)
        val limit = bytes.limit()
        val position = bytes.position()
        var result: CharBuffer = null
        try {
          bytes.flip()
          result = decoder.decode(bytes)
        } catch {
          case _: MalformedInputException =>
            bytes.limit(limit)
            bytes.position(position)
        }
        if (result == null) decodeNextLoop() else result
      }

      @tailrec
      def loop(): String = {
        val buffer = decodeNext()
        if (buffer == null) {
          return builder.toString()
        }
        val decoded = buffer.toString()
        if (decoded == "\n") {
          val len = builder.length()
          if (len > 0) {
            if (builder.charAt(len - 1) == '\r') {
              builder.deleteCharAt(len - 1)
            }
          }
          builder.toString()
        } else {
          builder.append(decoded)
          loop()
        }
      }

      loop()
    }

  /**
   * Prints a value to the standard output using the implicit `cats.Show` instance.
   *
   * @param a value to be printed to the standard output
   */
  def print[A: Show](a: A): F[Unit] = {
    val text = a.show
    F.blocking(System.out.print(text))
  }

  /**
   * Prints a value to the standard output followed by a new line using the implicit `cats.Show` instance.
   *
   * @param a value to be printed to the standard output
   */
  def println[A: Show](a: A): F[Unit] = {
    val text = a.show
    F.blocking(System.out.println(text))
  }

  /**
   * Prints a value to the standard error output using the implicit `cats.Show` instance.
   *
   * @param a value to be printed to the standard error output
   */
  def error[A: Show](a: A): F[Unit] = {
    val text = a.show
    F.blocking(System.err.print(text))
  }

  /**
   * Prints a value to the standard error output followed by a new line using the implicit `cast.Show` instance.
   *
   * @param a value to be printed to the standard error output
   */
  def errorln[A: Show](a: A): F[Unit] = {
    val text = a.show
    F.blocking(System.err.println(text))
  }
}

object Console {

  /**
   * Allows access to `Console` functionality for `F` data types that are [[Sync]].
   *
   * For printing to the standard output:
   * {{{
   *   Console[F].print("Hello")
   *   Console[F].println("Hello")
   * }}}
   *
   * For printing to the standard error:
   * {{{
   *   Console[F].error("Hello")
   *   Console[F].errorln("Hello")
   * }}}
   *
   * For reading from the standard input:
   * {{{
   *   Console[F].readLine
   * }}}
   */
  def apply[F[_]](implicit F: Sync[F]): Console[F] =
    new Console[F](F)
}
