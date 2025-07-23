/*
 * Copyright 2020-2025 Typelevel
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
package testkit

import cats.data.Chain
import cats.effect.testkit.TestConsole.TestStdIn.State.{Chunk, Line, PartialLine}
import cats.syntax.all._

import scala.concurrent.duration.DurationInt

import java.nio.charset.StandardCharsets

class TestConsoleSpec extends BaseSuite {
  private def chunk(value: String): Chunk = Chunk(value, StandardCharsets.UTF_8)

  real("TestConsole.print should append the formatted value to stdOut") {
    TestConsole.resource[IO].use {
      case (console, _, inspector) =>
        for {
          _ <- console.println(120)
          _ <- console.print("foo")
          stdOut <- inspector.stdOut
        } yield assertEquals(stdOut, "120\nfoo")
    }
  }

  real("TestConsole.error should append the formatted value to stdErr") {
    TestConsole.resource[IO].use {
      case (console, _, inspector) =>
        for {
          _ <- console.errorln(120)
          _ <- console.error("foo")
          stdOut <- inspector.stdErr
        } yield assertEquals(stdOut, "120\nfoo")
    }
  }

  real("TestStdIn.write should fail when closed") {
    TestConsole.resource[IO].allocated.flatMap {
      case ((_, stdIn, _), close) =>
        for {
          _ <- close
          result <- stdIn.write("foo").attempt
        } yield assertEquals(result.leftMap(_.getMessage), Left("Console is closed"))
    }
  }

  real("TestStdIn.write should remain waiting when waiting and line is not complete") {
    TestConsole.resource[IO].use {
      case (_, stdIn, inspector) =>
        for {
          _ <- stdIn.write("foo")
          _ <- stdIn.write("bar")
          state <- inspector.description
        } yield assertEquals(
          state,
          """|=== Activity Log ===
             |Writing to stdIn: foo
             |Writing to stdIn: bar
             |=== Current State ===
             |Waiting for write
             |Pending requests: 0
             |Partial line: 'foobar'""".stripMargin
        )
    }
  }

  real("TestStdIn.write should be ready when waiting and line is complete") {
    TestConsole.resource[IO].use {
      case (_, stdIn, inspector) =>
        for {
          _ <- stdIn.writeln("foo")
          state <- inspector.description
        } yield assertEquals(
          state,
          """|=== Activity Log ===
             |Writing line to stdIn: foo
             |=== Current State ===
             |Ready for read
             |No partial line
             |--- Complete Lines ---
             |foo""".stripMargin
        )
    }
  }

  real("TestStdIn.write should stay ready when already ready") {
    TestConsole.resource[IO].use {
      case (_, stdIn, inspector) =>
        for {
          _ <- stdIn.writeln("foo")
          _ <- stdIn.writeln("bar")
          state <- inspector.description
        } yield assertEquals(
          state,
          """|=== Activity Log ===
             |Writing line to stdIn: foo
             |Writing line to stdIn: bar
             |=== Current State ===
             |Ready for read
             |No partial line
             |--- Complete Lines ---
             |foo
             |bar""".stripMargin
        )
    }
  }

  real("TestStdIn.write should accumulate partial lines when already ready") {
    TestConsole.resource[IO].use {
      case (_, stdIn, inspector) =>
        for {
          _ <- stdIn.writeln("foo")
          _ <- stdIn.write("bar")
          _ <- stdIn.write("baz")
          state <- inspector.description
        } yield assertEquals(
          state,
          """|=== Activity Log ===
             |Writing line to stdIn: foo
             |Writing to stdIn: bar
             |Writing to stdIn: baz
             |=== Current State ===
             |Ready for read
             |Partial line: 'barbaz'
             |--- Complete Lines ---
             |foo""".stripMargin
        )
    }
  }

  real(
    "TestStdIn.write should retain partial lines when writing a new line and already ready") {
    TestConsole.resource[IO].use {
      case (_, stdIn, inspector) =>
        for {
          _ <- stdIn.writeln("foo")
          _ <- stdIn.write("bar")
          _ <- stdIn.write("baz")
          _ <- stdIn.writeln("qux")
          state <- inspector.description
        } yield assertEquals(
          state,
          """|=== Activity Log ===
             |Writing line to stdIn: foo
             |Writing to stdIn: bar
             |Writing to stdIn: baz
             |Writing line to stdIn: qux
             |=== Current State ===
             |Ready for read
             |No partial line
             |--- Complete Lines ---
             |foo
             |barbazqux""".stripMargin
        )
    }
  }

  real("TestStdIn.write should wake up multiple reads if the line has embedded newlines") {
    TestConsole.resource[IO].use {
      case (console, stdIn, inspector) =>
        TestControl.executeEmbed {
          (
            console.readLineWithCharset(StandardCharsets.UTF_8),
            console.readLineWithCharset(StandardCharsets.UTF_8).delayBy(10.millis),
            stdIn.writeln("foo\nbar").delayBy(20.millis)
          ).parMapN {
            case (fooRead, barRead, _) =>
              assertEquals((fooRead, barRead), ("foo", "bar"))
          } *> inspector.description.flatMap { log =>
            // Non-determinism is fun.
            // The reads receive the right values, but they may wake up out of
            // order.
            if (log == """|=== Activity Log ===
                          |Reading stdIn [id: 0]
                          |Reading stdIn [id: 1]
                          |Writing line to stdIn: foo
                          |bar
                          |Read from stdIn [id: 0]: foo
                          |Read from stdIn [id: 1]: bar
                          |=== Current State ===
                          |Waiting for write
                          |Pending requests: 0
                          |No partial line""".stripMargin) IO.unit
            else if (log == """|=== Activity Log ===
                               |Reading stdIn [id: 0]
                               |Reading stdIn [id: 1]
                               |Writing line to stdIn: foo
                               |bar
                               |Read from stdIn [id: 1]: bar
                               |Read from stdIn [id: 0]: foo
                               |=== Current State ===
                               |Waiting for write
                               |Pending requests: 0
                               |No partial line""".stripMargin) IO.unit
            else IO(fail("Unexpected activity log", clues(log)))
          }
        }
    }
  }

  real("TestConsole.readLineWithCharset should read only a single line from stdIn") {
    TestConsole.resource[IO].use {
      case (console, stdIn, _) =>
        for {
          _ <- stdIn.writeln("foo")
          _ <- stdIn.writeln("bar")
          actual <- console.readLineWithCharset(StandardCharsets.UTF_8)
        } yield assertEquals(actual, "foo")
    }
  }

  real("TestConsole.readLineWithCharset should block if the write isn't ready") {
    TestConsole.resource[IO].use {
      case (console, stdIn, inspector) =>
        TestControl.executeEmbed {
          (
            console.readLineWithCharset(StandardCharsets.UTF_8),
            console.readLineWithCharset(StandardCharsets.UTF_8).delayBy(10.millis),
            stdIn.writeln("foo").delayBy(20.millis),
            stdIn.writeln("bar").delayBy(30.millis)
          ).parMapN {
            case (fooRead, barRead, _, _) =>
              assertEquals((fooRead, barRead), ("foo", "bar"))
          } *> inspector.description.map {
            assertEquals(
              _,
              """|=== Activity Log ===
                 |Reading stdIn [id: 0]
                 |Reading stdIn [id: 1]
                 |Writing line to stdIn: foo
                 |Read from stdIn [id: 0]: foo
                 |Writing line to stdIn: bar
                 |Read from stdIn [id: 1]: bar
                 |=== Current State ===
                 |Waiting for write
                 |Pending requests: 0
                 |No partial line""".stripMargin
            )
          }
        }
    }
  }

  real("TestConsole should clean up any blocked reads when released") {
    TestConsole.resource[IO].allocated.flatMap {
      case ((console, stdIn, inspector), close) =>
        TestControl.executeEmbed {
          (
            console.readLineWithCharset(StandardCharsets.UTF_8).attempt,
            console
              .readLineWithCharset(StandardCharsets.UTF_8)
              .attempt
              .map(_.leftMap(_.getMessage))
              .delayBy(10.millis),
            stdIn.writeln("foo").attempt.delayBy(20.millis),
            close.delayBy(30.millis)
          ).parMapN {
            case (fooRead, barRead, write, _) =>
              assertEquals(
                (fooRead, barRead, write),
                (Right("foo"), Left("End Of File"), Right(()))
              )
          } *> inspector.description.flatMap { log =>
            // Non-determinism is fun.
            // The result itself is stable, but the failure may be logged before
            // or after the transition to 'Closed' is logged
            if (log == """|=== Activity Log ===
                          |Reading stdIn [id: 0]
                          |Reading stdIn [id: 1]
                          |Writing line to stdIn: foo
                          |Read from stdIn [id: 0]: foo
                          |Notified 1 pending read requests
                          |Read from stdIn failed [id: 1]: java.io.EOFException: End Of File
                          |Closed
                          |=== Current State ===
                          |Closed""".stripMargin) IO.unit
            else if (log == """|=== Activity Log ===
                               |Reading stdIn [id: 0]
                               |Reading stdIn [id: 1]
                               |Writing line to stdIn: foo
                               |Read from stdIn [id: 0]: foo
                               |Notified 1 pending read requests
                               |Closed
                               |Read from stdIn failed [id: 1]: java.io.EOFException: End Of File
                               |=== Current State ===
                               |Closed""".stripMargin) IO.unit
            else IO(fail("Unexpected activity log", clues(log)))
          }
        }
    }
  }

  test("PartialLine.append should not change when appending an empty chunk") {
    assertEquals(
      PartialLine.one(chunk("foo")).append(chunk("")),
      (Chain.empty[Line], PartialLine.one(chunk("foo")))
    )
  }

  test("PartialLine.append should replace when appending to an empty chunk") {
    assertEquals(
      PartialLine.one(chunk("")).append(chunk("foo")),
      (Chain.empty[Line], PartialLine.one(chunk("foo")))
    )
  }

  test(
    "PartialLine.append should append the incoming chunk when it does not contain a newline") {
    assertEquals(
      PartialLine.one(chunk("foo")).append(chunk("bar")),
      (Chain.empty[Line], PartialLine(Chain(chunk("foo"), chunk("bar"))))
    )
  }

  test(
    "PartialLine.append should emit and replace when the incoming chunk starts with a newline") {
    assertEquals(
      PartialLine.one(chunk("foo")).append(chunk("\nbar")),
      (Chain.one(Line.one(chunk("foo"))), PartialLine.one(chunk("bar")))
    )
  }

  test(
    "PartialLine.append should append the incoming chunk when it does not end with a newline") {
    assertEquals(
      PartialLine.one(chunk("foo")).append(chunk("bar")),
      (Chain.empty, PartialLine(Chain(chunk("foo"), chunk("bar"))))
    )
  }

  test("PartialLine.append should emit when the incoming chunk ends with a newline") {
    assertEquals(
      PartialLine.one(chunk("foo")).append(chunk("bar\n")),
      (Chain.one(Line(Chain(chunk("foo"), chunk("bar")))), PartialLine.empty)
    )
  }

  test("PartialLine.append should emit multiple if the incoming chunk has multiple newlines") {
    assertEquals(
      PartialLine.one(chunk("foo")).append(chunk("bar\nbaz\nqux")),
      (
        Chain(
          Line(Chain(chunk("foo"), chunk("bar"))),
          Line.one(chunk("baz"))
        ),
        PartialLine.one(chunk("qux"))
      )
    )
  }
}
