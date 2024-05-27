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
package std

import cats.Show
import cats.effect.kernel.Sync
import cats.syntax.all._

import org.specs2.matcher.MatchResult

import scala.concurrent.duration._
import scala.io.Source

import java.io.{
  ByteArrayInputStream,
  ByteArrayOutputStream,
  InputStream,
  PipedInputStream,
  PipedOutputStream,
  PrintStream
}
import java.nio.charset.{Charset, StandardCharsets}

class ConsoleJVMSpec extends BaseSpec {
  sequential

  private def printStream(out: ByteArrayOutputStream): Resource[IO, PrintStream] =
    Resource.make(IO(new PrintStream(out)))(ps => IO(ps.close()))

  private def replace(
      ps: PrintStream,
      get: () => PrintStream,
      set: PrintStream => Unit): IO[PrintStream] =
    for {
      out <- IO(get())
      _ <- IO(set(ps))
    } yield out

  private def restore(ps: PrintStream, set: PrintStream => Unit): IO[Unit] =
    for {
      _ <- IO(set(ps))
    } yield ()

  private def replaceStandardOut(ps: PrintStream): Resource[IO, Unit] =
    Resource.make(replace(ps, () => System.out, System.setOut))(restore(_, System.setOut)).void

  private def extractMessage(out: ByteArrayOutputStream): IO[String] =
    IO(new String(out.toByteArray(), StandardCharsets.UTF_8))

  private def standardOutTest(io: => IO[Unit]): IO[String] = {
    val test = for {
      out <- Resource.eval(IO(new ByteArrayOutputStream()))
      ps <- printStream(out)
      _ <- replaceStandardOut(ps)
    } yield out

    test.use(out => io.as(out)).flatMap(extractMessage)
  }

  private def replaceStandardErr(ps: PrintStream): Resource[IO, Unit] =
    Resource.make(replace(ps, () => System.err, System.setErr))(restore(_, System.setErr)).void

  private def replaceStandardIn(in: InputStream): Resource[IO, Unit] = {
    def replace(in: InputStream): IO[InputStream] =
      for {
        std <- IO(System.in)
        _ <- IO(System.setIn(in))
      } yield std

    def restore(in: InputStream): IO[Unit] =
      IO(System.setIn(in))

    Resource.make(replace(in))(restore).void
  }

  private def standardErrTest(io: => IO[Unit]): IO[String] = {
    val test = for {
      out <- Resource.eval(IO(new ByteArrayOutputStream()))
      ps <- printStream(out)
      _ <- replaceStandardErr(ps)
    } yield out

    test.use(out => io.as(out)).flatMap(extractMessage)
  }

  private def throwableToString(t: Throwable): String = {
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos)
    t.printStackTrace(ps)
    baos.toString
  }

  private def fileLines(name: String, charset: Charset): IO[List[String]] = {
    val acquire =
      IO.blocking(Source.fromResource(s"readline-test.$name.txt")(charset))
    val release = (src: Source) => IO(src.close())
    Resource
      .make(acquire)(release)
      .use(src => IO.interruptible(src.getLines().toList))
      .handleErrorWith(_ => IO.pure(Nil))
  }

  private def consoleReadLines(lines: List[String], charset: Charset): IO[List[String]] = {
    def inputStream: Resource[IO, InputStream] = {
      val acquire =
        IO {
          val bytes = lines.mkString(System.lineSeparator()).getBytes(charset)
          new ByteArrayInputStream(bytes)
        }
      val release = (in: InputStream) => IO(in.close())
      Resource.make(acquire)(release)
    }

    val test = for {
      in <- inputStream
      _ <- replaceStandardIn(in)
    } yield ()

    def loop(acc: List[String]): IO[List[String]] =
      Console[IO]
        .readLineWithCharset(charset)
        .flatMap(ln => loop(ln :: acc))
        .handleErrorWith(_ => IO.pure(acc.reverse))

    test.use(_ => loop(Nil))
  }

  private def readLineTest(name: String, charset: Charset): IO[MatchResult[List[String]]] =
    for {
      rawLines <- fileLines(name, charset)
      lines <- consoleReadLines(rawLines, charset)
      result <- IO(lines must beEqualTo(rawLines))
    } yield result

  "Console" should {
    "print to the standard output" in real {
      val message = "Message"
      standardOutTest(IO.print(message)).flatMap { msg =>
        IO {
          msg must beEqualTo(message)
        }
      }
    }

    "println to the standard output" in real {
      val message = "Message"
      standardOutTest(IO.println(message)).flatMap { msg =>
        IO {
          msg must beEqualTo(s"$message${System.lineSeparator()}")
        }
      }
    }

    "print to the standard error" in real {
      val error = "Error"
      standardErrTest(Console[IO].error(error)).flatMap { err =>
        IO {
          err must beEqualTo(error)
        }
      }
    }

    "println to the standard error" in real {
      val error = "Error"
      standardErrTest(Console[IO].errorln(error)).flatMap { err =>
        IO {
          err must beEqualTo(s"$error${System.lineSeparator()}")
        }
      }
    }

    "printStackTrace to the standard error output" in real {
      val e = new Throwable("error!")

      val stackTraceString = throwableToString(e)

      standardErrTest(Console[IO].printStackTrace(e)).flatMap { err =>
        IO {
          err must beEqualTo(stackTraceString)
        }
      }
    }

    "default printStackTrace implementation copies the throwable stack trace and prints it to the standard error" in real {

      final class DummyConsole[F[_]](implicit F: Sync[F]) extends Console[F] {
        def readLineWithCharset(charset: Charset): F[String] = F.pure("line")

        def print[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit] = F.unit

        def println[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit] = F.unit

        def error[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit] = {
          val text = a.show
          F.blocking(System.err.print(text))
        }

        def errorln[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit] = F.unit
      }

      val e = new Throwable("error!")

      val stackTraceString = throwableToString(e)

      val console = new DummyConsole[IO]

      standardErrTest(console.printStackTrace(e)).flatMap { err =>
        IO {
          err must beEqualTo(stackTraceString)
        }
      }
    }

    "read all lines from an ISO-8859-1 encoded file" in real {
      val cs = StandardCharsets.ISO_8859_1
      readLineTest(cs.name(), cs)
    }

    "read all lines from a US-ASCII encoded file" in real {
      val cs = StandardCharsets.US_ASCII
      readLineTest(cs.name(), cs)
    }

    "read all lines from a UTF-8 encoded file" in real {
      val cs = StandardCharsets.UTF_8
      readLineTest(cs.name(), cs)
    }

    "read all lines from a UTF-16 encoded file" in real {
      val cs = StandardCharsets.UTF_16
      readLineTest(cs.name(), cs)
    }

    "read all lines from a UTF-16BE encoded file" in real {
      val cs = StandardCharsets.UTF_16BE
      readLineTest(cs.name(), cs)
    }

    "read all lines from a UTF-16LE encoded file" in real {
      val cs = StandardCharsets.UTF_16LE
      readLineTest(cs.name(), cs)
    }

    "readLine is cancelable and does not lose lines" in real {
      IO(new PipedOutputStream).flatMap { out =>
        IO(new PipedInputStream(out)).flatMap { in =>
          replaceStandardIn(in).surround {
            for {
              read1 <- IO.readLine.timeout(100.millis).attempt
              _ <- IO(read1 should beLeft)
              _ <- IO(out.write("unblocked\n".getBytes()))
              read2 <- Console[IO].readLineWithCharset(StandardCharsets.US_ASCII).attempt
              _ <- IO(read2 should beLeft)
              read3 <- IO.readLine
              _ <- IO(read3 must beEqualTo("unblocked"))
            } yield ok
          }
        }
      }
    }
  }
}
