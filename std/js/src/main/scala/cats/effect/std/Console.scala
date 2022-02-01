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
import cats.effect.kernel.{Async, Sync}
import cats.syntax.all._

import scala.annotation.nowarn
import scala.scalajs.js
import scala.util.Try

import java.nio.charset.Charset

/**
 * Effect type agnostic `Console` with common methods to write to and read from the standard
 * console. Suited only for extremely simple console input and output.
 *
 * @example
 *   {{{ import cats.effect.std.Console import cats.effect.kernel.Sync import cats.syntax.all._
 *
 * implicit val console = Console.sync[F]
 *
 * def myProgram[F[_]: Console]: F[Unit] = for { _ <- Console[F].println("Please enter your
 * name: ") n <- Console[F].readLine _ <- if (n.nonEmpty) Console[F].println("Hello, " + n) else
 * Console[F].errorln("Name is empty!") } yield () }}}
 */
trait Console[F[_]] extends ConsoleCrossPlatform[F] {

  @deprecated("Not implemented for Scala.js. On Node.js consider using fs2.io.stdin.", "3.4.0")
  def readLine: F[String] =
    readLineWithCharset(Charset.defaultCharset())

  @deprecated("Not implemented for Scala.js. On Node.js consider using fs2.io.stdin.", "3.4.0")
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
   * Constructs a `Console` instance for `F` data types that are [[cats.effect.kernel.Async]].
   */
  def make[F[_]](implicit F: Async[F]): Console[F] = {

    val stdout = Try(js.Dynamic.global.process.stdout)
      .toOption
      .flatMap(Option(_))
      .filterNot(js.isUndefined(_))

    val stderr = Try(js.Dynamic.global.process.stderr)
      .toOption
      .flatMap(Option(_))
      .filterNot(js.isUndefined(_))

    stdout.zip(stderr).map {
      case (stdout, stderr) => new NodeJSConsole(stdout, stderr)
    } getOrElse new SyncConsole
  }

  @deprecated("Retaining for bincompat", "3.4.0")
  private[std] def make[F[_]](implicit F: Sync[F]): Console[F] =
    new SyncConsole[F]

  private[std] abstract class MapKConsole[F[_], G[_]](self: Console[F], f: F ~> G)
      extends Console[G] {
    def readLineWithCharset(charset: Charset): G[String] =
      f(self.readLineWithCharset(charset)): @nowarn("cat=deprecation")
  }

  private final class NodeJSConsole[F[_]](stdout: js.Dynamic, stderr: js.Dynamic)(
      implicit F: Async[F])
      extends Console[F] {

    private def write(writable: js.Dynamic, s: String): F[Unit] =
      F.async_[Unit] { cb =>
        if (writable.write(s).asInstanceOf[Boolean]) // no backpressure
          cb(Right(()))
        else // wait for drain event
          writable.once("drain", () => cb(Right(())))
        ()
      }

    private def writeln(writable: js.Dynamic, s: String): F[Unit] =
      F.delay(writable.cork()) *> // buffers until uncork
        write(writable, s) *>
        write(writable, "\n") *>
        F.delay(writable.uncork()).void

    def error[A](a: A)(implicit S: cats.Show[A]): F[Unit] = write(stderr, S.show(a))

    def errorln[A](a: A)(implicit S: cats.Show[A]): F[Unit] = writeln(stderr, S.show(a))

    def print[A](a: A)(implicit S: cats.Show[A]): F[Unit] = write(stdout, S.show(a))

    def println[A](a: A)(implicit S: cats.Show[A]): F[Unit] = writeln(stdout, S.show(a))

    def readLineWithCharset(charset: Charset): F[String] =
      F.raiseError(
        new UnsupportedOperationException(
          "Not implemented for Scala.js. On Node.js consider using fs2.io.stdin."))
  }

}
