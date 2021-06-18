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

package cats.effect
package std

import cats.Show
import cats.effect.kernel.Sync
import cats.syntax.all._

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.{Charset, StandardCharsets}

class ConsoleSpec extends BaseSpec {
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

  "Console" should {

    case class Foo(n: Int, b: Boolean)

    "select default Show.fromToString (IO)" in {
      IO.print(Foo(1, true)) // compilation test
      IO.println(Foo(1, true)) // compilation test
      true
    }

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

    "select default Show.fromToString (Console[IO])" in {
      Console[IO].print(Foo(1, true)) // compilation test
      Console[IO].println(Foo(1, true)) // compilation test
      Console[IO].error(Foo(1, true)) // compilation test
      Console[IO].errorln(Foo(1, true)) // compilation test
      true
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
  }
}
