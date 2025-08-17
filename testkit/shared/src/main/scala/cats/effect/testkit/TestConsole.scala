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

package cats.effect.testkit

import cats.{Applicative, ApplicativeThrow, Functor, Semigroupal, Show}
import cats.data.{Chain, NonEmptyChain}
import cats.effect.Concurrent
import cats.effect.kernel.{Deferred, Ref, Resource}
import cats.effect.std.{Console, Semaphore}
import cats.effect.testkit.TestConsole.{ConsoleClosedException, Op, TestStdIn}
import cats.syntax.all._

import scala.annotation.tailrec
import scala.util.control.NoStackTrace

import java.io.EOFException
import java.nio.charset.Charset

/**
 * Implement a test version of [[cats.effect.std.Console]]
 */
final class TestConsole[F[_]: ApplicativeThrow](
    stdIn: TestStdIn[F],
    inspector: TestConsole.Inspector[F]
) extends Console[F] {
  import inspector.log

  override def readLineWithCharset(charset: Charset): F[String] = stdIn.readLine(charset)
  override def print[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit] = log(
    Op.Print(a.show))
  override def println[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit] = log(
    Op.Println(a.show))
  override def error[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit] = log(
    Op.Error(a.show))
  override def errorln[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit] = log(
    Op.Errorln(a.show))

  private[testkit] def close: F[Unit] =
    stdIn.close.recover { case ConsoleClosedException() => () } *> inspector.log(Op.Closed)
}
object TestConsole {

  /**
   * Create a resource which instantiates and closes a [[TestConsole]]
   *
   * This is the preferred usage pattern, as it ensures that no fibers are left blocked on calls
   * to [[TestConsole.readLineWithCharset]]
   */
  def resource[F[_]: Concurrent]: Resource[F, (TestConsole[F], TestStdIn[F], Inspector[F])] =
    Resource.make[F, (TestConsole[F], TestStdIn[F], Inspector[F])](unsafe[F])(_._1.close)

  private def unsafe[F[_]: Concurrent]: F[(TestConsole[F], TestStdIn[F], Inspector[F])] =
    for {
      stdInStateRef <- Ref.of[F, TestStdIn.State[F]](TestStdIn.State.waiting[F])
      inspector <- Inspector.default(stdInStateRef)
      stdIn <- TestStdIn.default(stdInStateRef, inspector)
    } yield (new TestConsole[F](stdIn, inspector), stdIn, inspector)

  private[testkit] final case class ConsoleClosedException()
      extends IllegalStateException("Console is closed")
      with NoStackTrace

  /**
   * Allows inspection of the state of a [[TestConsole]]
   */
  trait Inspector[F[_]] {

    /**
     * @return
     *   The current contents of the associated [[TestConsole]]'s stdOut
     */
    def stdOut: F[String]

    /**
     * @return
     *   The current contents of the associated [[TestConsole]]'s stdErr
     */
    def stdErr: F[String]

    /**
     * @return
     *   A human-readable description of the activity log and current status of this instance.
     *
     * Handy for debugging failing or blocked tests.
     */
    def description: F[String]

    /**
     * Provides access to lower level inspections.
     *
     * This is generally discouraged as it tends to make tests more brittle.
     */
    def lowLevel: Inspector.LowLevel[F]

    private[testkit] def log(operation: Op): F[Unit]
    private[testkit] def freeze: F[Inspector[F]]
  }
  object Inspector {
    trait LowLevel[F[_]] extends Inspector[F] {

      /**
       * @return
       *   The list of user operations on stdOut
       */
      def stdOutOperations: F[List[Op.StdOutOp]]

      /**
       * @return
       *   The list of user operations on stdErr
       */
      def stdErrOperations: F[List[Op.StdErrOp]]

      /**
       * @return
       *   The list of user operations on stdIn
       */
      def stdInOperations: F[List[Op.StdInOp]]

      /**
       * @return
       *   The list of all operations
       */
      def operationsLog: F[List[Op]]
    }

    def default[F[_]: Concurrent](stdInStateRef: Ref[F, TestStdIn.State[F]]): F[Inspector[F]] =
      Ref.empty[F, Chain[Op]].map { operationRef =>
        new Default[F](operationRef.get, stdInStateRef.get) {
          override private[testkit] def log(operation: Op): F[Unit] =
            operationRef.update(_.append(operation))

          override private[testkit] def freeze: F[Inspector[F]] =
            (stdInStateRef.get, operationRef.get).mapN(frozen(_, _))
        }
      }

    def frozen[F[_]: Applicative](
        finalState: TestStdIn.State[F],
        logs: Chain[Op]): Inspector[F] =
      new Default[F](logs.pure[F], finalState.pure[F]) {
        override private[testkit] def log(operation: Op): F[Unit] = Applicative[F].unit

        override private[testkit] def freeze: F[Inspector[F]] = this.pure[F].widen
      }

    private abstract class Default[F[_]: Functor: Semigroupal](
        operations: F[Chain[Op]],
        state: F[TestStdIn.State[F]])
        extends Inspector[F]
        with LowLevel[F] {
      override def stdOut: F[String] = operations.map { opLog =>
        opLog
          .flatMap {
            case Op.Print(value) => Chain.one(value)
            case Op.Println(value) => Chain(value, "\n")
            case _ => Chain.empty
          }
          .mkString_("")
      }

      override def stdErr: F[String] = operations.map { opLog =>
        opLog
          .flatMap {
            case Op.Error(value) => Chain.one(value)
            case Op.Errorln(value) => Chain(value, "\n")
            case _ => Chain.empty
          }
          .mkString_("")
      }

      override def description: F[String] =
        (operations.map(_.mkString_("\n")), state.map(_.describe)).mapN { (logStr, stateStr) =>
          s"""|=== Activity Log ===
              |$logStr
              |=== Current State ===
              |$stateStr""".stripMargin
        }

      override def lowLevel: LowLevel[F] = this

      override def stdOutOperations: F[List[Op.StdOutOp]] = operations.map(_.flatMap {
        case op: Op.StdOutOp => Chain.one(op)
        case _ => Chain.empty
      }.toList)

      override def stdErrOperations: F[List[Op.StdErrOp]] = operations.map(_.flatMap {
        case op: Op.StdErrOp => Chain.one(op)
        case _ => Chain.empty
      }.toList)

      override def stdInOperations: F[List[Op.StdInOp]] = operations.map(_.flatMap {
        case op: Op.StdInOp => Chain.one(op)
        case _ => Chain.empty
      }.toList)

      override def operationsLog: F[List[Op]] = operations.map(_.toList)
    }
  }

  sealed trait Op
  object Op {
    sealed trait StdOutOp
    sealed trait StdErrOp
    sealed trait StdInOp

    final case class Error(value: String) extends Op with StdErrOp
    final case class Errorln(value: String) extends Op with StdErrOp
    final case class Print(value: String) extends Op with StdOutOp
    final case class Println(value: String) extends Op with StdOutOp
    final case class Write(value: String) extends Op with StdInOp
    final case class Writeln(value: String) extends Op with StdInOp
    final case class ReadAttempted(id: Int) extends Op with StdInOp
    final case class ReadSuccess(id: Int, line: String) extends Op with StdInOp
    final case class ReadFailure(id: Int, throwable: Throwable) extends Op with StdInOp
    final case class DiscardStdInContents(lines: Long, bytes: Long) extends Op
    final case class NotifyPendingReads(requests: Long) extends Op
    case object Closed extends Op

    implicit val show: Show[Op] = Show.show {
      case Error(value) => s"error($value)"
      case Errorln(value) => s"errorln($value)"
      case Print(value) => s"print($value)"
      case Println(value) => s"println($value)"
      case Write(value) => s"Writing to stdIn: $value"
      case Writeln(value) => s"Writing line to stdIn: $value"
      case ReadAttempted(id) => s"Reading stdIn [id: $id]"
      case ReadSuccess(id, line) => s"Read from stdIn [id: $id]: $line"
      case ReadFailure(id, throwable) => s"Read from stdIn failed [id: $id]: $throwable"
      case DiscardStdInContents(lines, bytes) =>
        s"Discarded $lines lines and $bytes bytes from stdIn"
      case NotifyPendingReads(requests) => s"Notified $requests pending read requests"
      case Closed => "Closed"
    }
  }

  trait TestStdIn[F[_]] {

    /**
     * Write a string to the simulated stdIn
     *
     * Blocked calls to [[TestConsole.readLineWithCharset]] will be woken up if `str` contains
     * one or more lines.
     *
     * @note
     *   Blocked calls will be woken in a first-in-first-out order.
     */
    def write[A](value: A, charset: Charset = Charset.defaultCharset())(
        implicit S: Show[A] = Show.fromToString[A]): F[Unit]

    /**
     * Write a string and a newline to the simulated stdIn
     *
     * At least one blocked call to [[TestConsole.readLineWithCharset]] will be woken up, if it
     * exists.
     *
     * @note
     *   Blocked calls will be woken in a first-in-first-out order.
     */
    def writeln[A](value: A, charset: Charset = Charset.defaultCharset())(
        implicit S: Show[A] = Show.fromToString[A]): F[Unit]

    private[testkit] def readLine(charset: Charset): F[String]
    private[testkit] def close: F[Unit]
  }

  object TestStdIn {
    def default[F[_]](stateRef: Ref[F, TestStdIn.State[F]], inspector: Inspector[F])(
        implicit F: Concurrent[F]): F[TestStdIn[F]] =
      (Semaphore[F](1L), Ref.of[F, Int](0)).mapN { (semaphore, readIdRef) =>
        new TestStdIn[F] {
          import inspector.log

          private def streamClosed = new EOFException("End Of File")

          override def write[A](value: A, charset: Charset = Charset.defaultCharset())(
              implicit S: Show[A] = Show.fromToString[A]): F[Unit] =
            log(Op.Write(value.show)) *> writeImpl(State.Chunk(value.show, charset))

          override def writeln[A](value: A, charset: Charset = Charset.defaultCharset())(
              implicit S: Show[A] = Show.fromToString[A]): F[Unit] =
            log(Op.Writeln(value.show)) *> writeImpl(State.Chunk(show"$value\n", charset))

          private def writeImpl(chunk: State.Chunk): F[Unit] =
            if (chunk.isEmpty) F.unit
            else
              semaphore.permit.use { _ =>
                stateRef.get.flatMap {
                  case State.Closed() => F.raiseError(ConsoleClosedException())
                  case ready: TestStdIn.State.Ready[F] => stateRef.set(ready.push(chunk))
                  case oldState: TestStdIn.State.Waiting[F] =>
                    val (lines, newBuffer) = oldState.buffer.append(chunk)
                    if (lines.isEmpty) stateRef.set(oldState.replaceBuffer(newBuffer))
                    else {
                      def loop(
                          remainingLines: Chain[State.Line],
                          remainingRequests: Chain[Deferred[F, Either[Throwable, Array[Byte]]]])
                          : F[State[F]] =
                        (remainingLines.uncons, remainingRequests.uncons) match {
                          case (None, None) => State.waiting[F].pure[F]
                          case (None, Some(_)) =>
                            State.waiting[F](remainingRequests, newBuffer).pure[F]
                          case (Some((nextLine, otherLines)), None) =>
                            State.ready[F](nextLine, otherLines, newBuffer).pure[F]
                          case (
                                Some((nextLine, otherLines)),
                                Some((nextRequest, otherRequests))) =>
                            nextRequest.complete(nextLine.bytes.asRight) >> loop(
                              otherLines,
                              otherRequests)
                        }

                      loop(lines, oldState.requests).flatMap(stateRef.set)
                    }
                }
              }

          override private[testkit] def readLine(charset: Charset): F[String] =
            readIdRef.getAndUpdate(_ + 1).flatMap { readId =>
              semaphore
                .permit
                .use { _ =>
                  log(Op.ReadAttempted(readId)) *>
                    stateRef.get.flatMap {
                      case State.Closed() =>
                        F.raiseError[Deferred[F, Either[Throwable, Array[Byte]]]](streamClosed)
                      case ready: TestStdIn.State.Ready[F] =>
                        val (line, newState) = ready.shift

                        stateRef.set(newState) *>
                          Deferred[F, Either[Throwable, Array[Byte]]].flatTap(
                            _.complete(line.bytes.asRight))
                      case waiting: TestStdIn.State.Waiting[F] =>
                        Deferred[F, Either[Throwable, Array[Byte]]].flatTap(d =>
                          stateRef.set(waiting.addRequest(d)))
                    }
                }
                .flatMap(_.get)
                .flatMap(_.traverse(bytes =>
                  Concurrent[F].catchNonFatal(new String(bytes, charset))))
                .flatTap {
                  case Left(ex) => log(Op.ReadFailure(readId, ex))
                  case Right(line) => log(Op.ReadSuccess(readId, line))
                }
                .rethrow
            }

          override private[testkit] def close: F[Unit] =
            semaphore.permit.use { _ =>
              stateRef.get.flatMap {
                case State.Closed() => F.unit
                case State.Ready(lines, partial) =>
                  log(Op.DiscardStdInContents(lines.length, partial.length))
                    .unlessA(partial.isEmpty) *>
                    stateRef.set(State.closed)
                case State.Waiting(requests, buffer) =>
                  log(Op.DiscardStdInContents(0, buffer.length)).unlessA(buffer.isEmpty) *>
                    log(Op.NotifyPendingReads(requests.length)).unlessA(requests.isEmpty) *>
                    requests.traverse_(_.complete(streamClosed.asLeft).attempt) *>
                    stateRef.set(State.closed)
              }
            }
        }
      }

    private[testkit] sealed trait State[F[_]] {
      def describe: String = this match {
        case State.Closed() => "Closed"
        case State.Ready(lines, partial) =>
          val linesStr = lines.mkString_("\n")
          val partialStr =
            if (partial.isEmpty) "No partial line"
            else s"Partial line: '${partial.render}'"

          s"""Ready for read
             |$partialStr
             |--- Complete Lines ---
             |$linesStr""".stripMargin
        case State.Waiting(requests, buffer) =>
          val bufferStr =
            if (buffer.isEmpty) "No partial line"
            else s"Partial line: '${buffer.render}'"
          s"""Waiting for write
             |Pending requests: ${requests.length}
             |$bufferStr""".stripMargin
      }
    }
    private[testkit] object State {
      def closed[F[_]]: State[F] = Closed()

      def ready[F[_]](
          firstLine: Line,
          otherLines: Chain[Line],
          partial: PartialLine): State[F] =
        Ready(NonEmptyChain.fromChainPrepend(firstLine, otherLines), partial)

      def waiting[F[_]]: State[F] = Waiting(Chain.empty, PartialLine.empty)
      def waiting[F[_]](buffer: PartialLine): State[F] = Waiting(Chain.empty, buffer)
      def waiting[F[_]](
          requests: Chain[Deferred[F, Either[Throwable, Array[Byte]]]],
          buffer: PartialLine): State[F] = Waiting(requests, buffer)

      final case class Chunk(value: String, charset: Charset) {
        def bytes: Array[Byte] = value.getBytes(charset)

        def isEmpty: Boolean = value.isEmpty

        def modify(f: String => String): Chunk = Chunk(f(value), charset)

        def split(char: Char): Option[(Chunk, Chunk)] = {
          val idx = value.indexOf(char.toInt)
          if (idx === -1) None
          else {
            val (head, tail) = value.splitAt(idx)
            Some((Chunk(head, charset), Chunk(tail.drop(1), charset)))
          }
        }
      }

      object Chunk {
        implicit val show: Show[Chunk] = Show.show(_.value)
      }

      /**
       * Chunks of a line which cannot be read from stdIn until a newline is written
       */
      final case class PartialLine(chunks: Chain[Chunk]) {
        def isEmpty: Boolean = chunks.forall(_.isEmpty)

        def length: Long = chunks.map(_.bytes.length.toLong).combineAll

        def render: String = chunks.mkString_("")

        def toLine: Line = Line(chunks)

        def append(chunk: Chunk): (Chain[Line], PartialLine) =
          if (chunk.value.startsWith("\n"))
            PartialLine.empty.append(chunk.modify(_.drop(1))).leftMap(_.prepend(toLine))
          else if (chunk.value.endsWith("\n")) {
            val (lines, lastLine) = append(chunk.modify(_.dropRight(1)))
            lines.append(lastLine.toLine) -> PartialLine.empty
          } else {
            if (chunk.isEmpty) (Chain.empty, this)
            else {
              @tailrec
              def loop(accum: Chain[Line], remaining: Chunk): (Chain[Line], PartialLine) =
                if (remaining.isEmpty) (accum, PartialLine.empty)
                else {
                  remaining.split('\n') match {
                    case None => (accum, PartialLine.one(remaining))
                    case Some((head, tail)) => loop(accum.append(Line.one(head)), tail)
                  }
                }

              chunk.split('\n') match {
                case Some((head, tail)) =>
                  loop(Chain.one(Line(chunks.append(head))), tail)
                case None =>
                  if (isEmpty) (Chain.empty, PartialLine.one(chunk))
                  else (Chain.empty, PartialLine(chunks.append(chunk)))
              }
            }
          }
      }

      object PartialLine {
        def one(c: Chunk): PartialLine = PartialLine(Chain.one(c))

        def empty: PartialLine = PartialLine(Chain.empty)
      }

      /**
       * Lines ready to be read from stdIn
       */
      final case class Line(chunks: Chain[Chunk]) {
        def isEmpty: Boolean = chunks.forall(_.isEmpty)

        def render: String = chunks.mkString_("")

        def bytes: Array[Byte] =
          chunks.map(_.bytes).toVector.toArray.flatten
      }

      object Line {
        def one(chunk: Chunk): Line = Line(Chain.one(chunk))

        def empty: Line = Line(Chain.empty)

        implicit val show: Show[Line] = Show.show(_.render)
      }

      /**
       * StdIn will reject reads and writes
       */
      final case class Closed[F[_]]() extends State[F]

      /**
       * StdIn has at least one line ready to be read
       *
       * Transitions to Waiting when `lines` can no longer be created
       */
      final case class Ready[F[_]](lines: NonEmptyChain[Line], partial: PartialLine)
          extends State[F] {
        def push(chunk: Chunk): State[F] = {
          val (newLines, newPartial) = partial.append(chunk)
          Ready[F](lines.appendChain(newLines), newPartial)
        }

        def shift: (Line, State[F]) = {
          val newState =
            NonEmptyChain.fromChain(lines.tail).fold(waiting[F](partial))(Ready(_, partial))

          (lines.head, newState)
        }
      }

      /**
       * StdIn cannot accept writes because it doesn't have at least one complete line
       *
       * Transitions to Ready when a newline is written to the stream
       */
      final case class Waiting[F[_]](
          requests: Chain[Deferred[F, Either[Throwable, Array[Byte]]]],
          buffer: PartialLine)
          extends State[F] {
        def replaceBuffer(newBuffer: PartialLine): State[F] =
          Waiting(requests, newBuffer)

        def addRequest(request: Deferred[F, Either[Throwable, Array[Byte]]]): State[F] =
          Waiting(requests.append(request), buffer)
      }
    }
  }
}
