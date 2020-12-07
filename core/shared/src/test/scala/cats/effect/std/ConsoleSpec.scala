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

package cats.effect
package std

import cats.syntax.all._

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

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
  }
}
