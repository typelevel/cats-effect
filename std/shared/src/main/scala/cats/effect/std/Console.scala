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

/*
 * This is an adapted version of the code originally found in
 * https://github.com/profunktor/console4cats, by Gabriel Volpe.
 */

package cats.effect.std

import cats.kernel.Monoid
import cats.{~>, Applicative, Functor, Show}
import cats.data.{EitherT, IorT, Kleisli, OptionT, ReaderWriterStateT, StateT, WriterT}
import cats.effect.kernel.Sync
import cats.syntax.show._

import scala.annotation.tailrec

import java.lang.{StringBuilder => JStringBuilder}
import java.io.{ByteArrayOutputStream, EOFException, PrintStream}
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{Charset, CodingErrorAction, MalformedInputException}

/**
 * Effect type agnostic `Console` with common methods to write to and read from
 * the standard console. Suited only for extremely simple console input and
 * output.
 *
 * @example {{{
 *   import cats.effect.std.Console
 *   import cats.effect.kernel.Sync
 *   import cats.syntax.all._
 *
 *   implicit val console = Console.sync[F]
 *
 *   def myProgram[F[_]: Console]: F[Unit] =
 *     for {
 *       _ <- Console[F].println("Please enter your name: ")
 *       n <- Console[F].readLine
 *       _ <- if (n.nonEmpty) Console[F].println("Hello, " + n)
 *            else Console[F].errorln("Name is empty!")
 *     } yield ()
 * }}}
 */
trait Console[F[_]] { self =>

  /**
   * Reads a line as a string from the standard input using the platform's
   * default charset, as per `java.nio.charset.Charset.defaultCharset()`.
   *
   * The effect can raise a `java.io.EOFException` if no input has been consumed
   * before the EOF is observed. This should never happen with the standard
   * input, unless it has been replaced with a finite `java.io.InputStream`
   * through `java.lang.System#setIn` or similar.
   *
   * @return an effect that describes reading the user's input from the standard
   *         input as a string
   */
  def readLine: F[String] =
    readLineWithCharset(Charset.defaultCharset())

  /**
   * Reads a line as a string from the standard input using the provided
   * charset.
   *
   * The effect can raise a `java.io.EOFException` if no input has been consumed
   * before the EOF is observed. This should never happen with the standard
   * input, unless it has been replaced with a finite `java.io.InputStream`
   * through `java.lang.System#setIn` or similar.
   *
   * @param charset the `java.nio.charset.Charset` to be used when decoding the
   *                input stream
   * @return an effect that describes reading the user's input from the standard
   *         input as a string
   */
  def readLineWithCharset(charset: Charset): F[String]

  /**
   * Prints a value to the standard output using the implicit `cats.Show`
   * instance.
   *
   * @param a value to be printed to the standard output
   * @param S implicit `cats.Show[A]` instance, defaults to `cats.Show.fromToString`
   */
  def print[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit]

  /**
   * Prints a value to the standard output followed by a new line using the
   * implicit `cats.Show` instance.
   *
   * @param a value to be printed to the standard output
   * @param S implicit `cats.Show[A]` instance, defaults to `cats.Show.fromToString`
   */
  def println[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit]

  /**
   * Prints a value to the standard error output using the implicit `cats.Show`
   * instance.
   *
   * @param a value to be printed to the standard error output
   * @param S implicit `cats.Show[A]` instance, defaults to `cats.Show.fromToString`
   */
  def error[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit]

  /**
   * Prints a value to the standard error output followed by a new line using
   * the implicit `cast.Show` instance.
   *
   * @param a value to be printed to the standard error output
   * @param S implicit `cats.Show[A]` instance, defaults to `cats.Show.fromToString`
   */
  def errorln[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit]

  /**
   * Prints the stack trace of the given Throwable to standard error output.
   */
  def printStackTrace(t: Throwable): F[Unit] = {
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos)
    t.printStackTrace(ps)
    error(baos.toString)
  }

  /**
   * Modifies the context in which this console operates using the natural
   * transformation `f`.
   *
   * @return a console in the new context obtained by mapping the current one
   *         using `f`
   */
  def mapK[G[_]](f: F ~> G): Console[G] =
    new Console[G] {
      def readLineWithCharset(charset: Charset): G[String] =
        f(self.readLineWithCharset(charset))

      def print[A](a: A)(implicit S: Show[A]): G[Unit] =
        f(self.print(a))

      def println[A](a: A)(implicit S: Show[A]): G[Unit] =
        f(self.println(a))

      def error[A](a: A)(implicit S: Show[A]): G[Unit] =
        f(self.error(a))

      def errorln[A](a: A)(implicit S: Show[A]): G[Unit] =
        f(self.errorln(a))

      override def printStackTrace(t: Throwable): G[Unit] =
        f(self.printStackTrace(t))
    }
}

object Console {

  /**
   * Summoner method for `Console` instances.
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
  def apply[F[_]](implicit C: Console[F]): C.type = C

  /**
   * Constructs a `Console` instance for `F` data types that are [[cats.effect.kernel.Sync]].
   */
  def make[F[_]](implicit F: Sync[F]): Console[F] =
    new SyncConsole[F]

  /**
   * [[Console]] instance built for `cats.data.EitherT` values initialized with
   * any `F` data type that also implements `Console`.
   */
  implicit def catsEitherTConsole[F[_]: Console: Functor, L]: Console[EitherT[F, L, *]] =
    Console[F].mapK(EitherT.liftK)

  /**
   * [[Console]] instance built for `cats.data.Kleisli` values initialized with
   * any `F` data type that also implements `Console`.
   */
  implicit def catsKleisliConsole[F[_]: Console, R]: Console[Kleisli[F, R, *]] =
    Console[F].mapK(Kleisli.liftK)

  /**
   * [[Console]] instance built for `cats.data.OptionT` values initialized with
   * any `F` data type that also implements `Console`.
   */
  implicit def catsOptionTConsole[F[_]: Console: Functor]: Console[OptionT[F, *]] =
    Console[F].mapK(OptionT.liftK)

  /**
   * [[Console]] instance built for `cats.data.StateT` values initialized with
   * any `F` data type that also implements `Console`.
   */
  implicit def catsStateTConsole[F[_]: Console: Applicative, S]: Console[StateT[F, S, *]] =
    Console[F].mapK(StateT.liftK)

  /**
   * [[Console]] instance built for `cats.data.WriterT` values initialized with
   * any `F` data type that also implements `Console`.
   */
  implicit def catsWriterTConsole[
      F[_]: Console: Applicative,
      L: Monoid
  ]: Console[WriterT[F, L, *]] =
    Console[F].mapK(WriterT.liftK)

  /**
   * [[Console]] instance built for `cats.data.IorT` values initialized with any
   * `F` data type that also implements `Console`.
   */
  implicit def catsIorTConsole[F[_]: Console: Functor, L]: Console[IorT[F, L, *]] =
    Console[F].mapK(IorT.liftK)

  /**
   * [[Console]] instance built for `cats.data.ReaderWriterStateT` values
   * initialized with any `F` data type that also implements `Console`.
   */
  implicit def catsReaderWriterStateTConsole[
      F[_]: Console: Applicative,
      E,
      L: Monoid,
      S
  ]: Console[ReaderWriterStateT[F, E, L, S, *]] =
    Console[F].mapK(ReaderWriterStateT.liftK)

  private final class SyncConsole[F[_]](implicit F: Sync[F]) extends Console[F] {
    def readLineWithCharset(charset: Charset): F[String] =
      F.interruptible(false) {
        val in = System.in
        val decoder = charset
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val bytes = ByteBuffer.allocate(64)
        val builder = new JStringBuilder()

        def decodeNext(): CharBuffer = {
          bytes.clear()
          decodeNextLoop()
        }

        @tailrec
        def decodeNextLoop(): CharBuffer = {
          val b = in.read()
          if (b == -1) null
          else {
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
        }

        @tailrec
        def loop(): String = {
          val buffer = decodeNext()
          if (buffer == null) {
            val result = builder.toString()
            if (result.nonEmpty) result
            else throw new EOFException()
          } else {
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
        }

        loop()
      }

    def print[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit] = {
      val text = a.show
      F.blocking(System.out.print(text))
    }

    def println[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit] = {
      val text = a.show
      F.blocking(System.out.println(text))
    }

    def error[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit] = {
      val text = a.show
      F.blocking(System.err.print(text))
    }

    def errorln[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit] = {
      val text = a.show
      F.blocking(System.err.println(text))
    }

    override def printStackTrace(t: Throwable): F[Unit] =
      F.blocking(t.printStackTrace())
  }
}
