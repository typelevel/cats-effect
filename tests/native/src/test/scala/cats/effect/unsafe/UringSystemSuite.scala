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
package unsafe

import cats.effect.std.CountDownLatch
import cats.effect.unsafe.UringSystem.liburingOps._
import cats.syntax.all._

import scala.concurrent.duration._
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno._
import scala.scalanative.posix.fcntl._
import scala.scalanative.posix.string._
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import java.io.IOException

class UringSystemSuite extends BaseSuite {

  override def munitIgnore: Boolean = !LinktimeInfo.isLinux

  private[this] var uringRuntime: IORuntime = _

  override def runtime(): IORuntime = uringRuntime

  override def beforeAll(): Unit = {
    val (blocking, blockDown) =
      IORuntime.createDefaultBlockingExecutionContext(
        threadPrefix = s"io-blocking-${getClass.getName}")
    val (compute, api, compDown) =
      IORuntime.createWorkStealingComputeThreadPool(
        threadPrefix = s"io-compute-${getClass.getName}",
        blockerThreadPrefix = s"io-blocker-${getClass.getName}",
        pollingSystem = UringSystem
      )
    uringRuntime = IORuntime(
      compute,
      blocking,
      compute,
      List(api),
      { () =>
        compDown()
        blockDown()
      },
      IORuntimeConfig()
    )
  }

  override def afterAll(): Unit =
    if (uringRuntime ne null) uringRuntime.shutdown()

  real("start the UringSystem") {
    UringSystem.Uring.get.map(uring => assert(uring ne null))
  }

  real("submit a nop SQE and resume on completion") {
    UringSystem
      .Uring
      .get
      .flatMap { uring => uring.call(io_uring_prep_nop) }
      .map(rtn => assertEquals(rtn, 0))
  }

  real("submit a nop SQE and resume on completion many times in a row") {
    UringSystem.Uring.get.flatMap { uring =>
      val op = uring.call(io_uring_prep_nop).map(rtn => assertEquals(rtn, 0))
      op.replicateA_(20)
    }
  }

  real("submit nop SQEs in parallel and resume on completion") {
    UringSystem.Uring.get.flatMap { uring =>
      val op = uring.call(io_uring_prep_nop).map(rtn => assertEquals(rtn, 0))
      op.parReplicateA_(10)
    }
  }

  real("submit nop SQEs in parallel and resume on completion many times in a row") {
    UringSystem.Uring.get.flatMap { uring =>
      val op = uring.call(io_uring_prep_nop).map(rtn => assertEquals(rtn, 0))
      op.replicateA_(20).parReplicateA_(10)
    }
  }

  real("bracket runs the release callback on Resource cleanup") {
    Ref.of[IO, Boolean](false).flatMap { released =>
      UringSystem.Uring.get.flatMap { uring =>
        uring.bracket(io_uring_prep_nop)(_ => released.set(true)).use(_ => IO.unit)
      } *> released.get.map(assert(_))
    }
  }

  real("cancel a pending poll_add") {
    pipeHandle.use {
      case (readFd, _) =>
        Resource
          .eval(FileDescriptorPoller.get)
          .flatMap(_.registerFileDescriptor(readFd, true, false))
          .use { handle =>
            Deferred[IO, Unit].flatMap { entered =>
              val read =
                handle.pollReadRec(()) { _ => entered.complete(()).void *> IO(Left(())) }
              read.start.flatMap { fiber =>
                entered.get *> fiber.cancel *> fiber.join.map(oc => assert(oc.isCanceled))
              }
            }
          }
    }
  }

  real("notify read-ready events") {
    mkPipe.use { pipe =>
      for {
        buf <- IO(new Array[Byte](4))
        _ <- pipe.write(Array[Byte](1, 2, 3), 0, 3).background.surround(pipe.read(buf, 0, 3))
        _ <- pipe.write(Array[Byte](42), 0, 1).background.surround(pipe.read(buf, 3, 1))
      } yield assertEquals(buf.toList, List[Byte](1, 2, 3, 42))
    }
  }

  real("handle lots of simultaneous events") {
    def test(n: Int) = mkPipe.replicateA(n).use { pipes =>
      CountDownLatch[IO](n).flatMap { latch =>
        pipes
          .traverse_ { pipe =>
            (pipe.read(new Array[Byte](1), 0, 1) *> latch.release).background
          }
          .surround {
            IO { // trigger all the pipes at once
              pipes.foreach { pipe =>
                unistd.write(pipe.writeFd, Array[Byte](42).atUnsafe(0), 1.toCSize)
              }
            }.background.surround(latch.await)
          }
      }
    }

    // multiples of 64 to exercise ready queue draining logic
    test(64) *> test(128) *>
      test(1000) // a big, non-64-multiple
  }

  real("hang if never ready") {
    mkPipe.use { pipe =>
      pipe
        .read(new Array[Byte](1), 0, 1)
        .map(_ => fail("shouldn't get there"))
        .timeoutTo(1.second, IO.unit)
    }
  }

  real("pollReadRec reads until EPOLLHUP then terminates with EOF") {
    mkReadOnlyPipe.use {
      case (readFd, writeFd, readHandle) =>
        for {
          buf <- IO(new Array[Byte](1))
          gate <- Deferred[IO, Unit]
          _ <- {
            readHandle.pollReadRec(()) { _ =>
              IO(guard(unistd.read(readFd, buf.atUnsafe(0), 1.toCSize))).flatTap {
                case Left(_) => gate.complete(())
                case Right(_) => IO.unit
              }
            }
          }.background.use { readerIO =>
            gate.get >> IO {
              unistd.close(writeFd)
            } >> readerIO
          }
        } yield ()
    }
  }

  real("pollWriteRec writes through a full pipe buffer") {
    val size = 256 * 1024
    mkPipe.use { pipe =>
      val payload = Array.tabulate[Byte](size)(i => (i & 0xff).toByte)
      val received = new Array[Byte](size)
      (
        pipe.writeAll(payload),
        pipe.readN(received)
      ).parTupled *> IO(assertEquals(received.toList, payload.toList))
    }
  }

  /////////////////////////////////////////////////////////////////
  // Helpers
  /////////////////////////////////////////////////////////////////

  private final class Pipe(
      val readFd: Int,
      val writeFd: Int,
      readHandle: FileDescriptorPollHandle,
      writeHandle: FileDescriptorPollHandle
  ) {
    def read(buf: Array[Byte], offset: Int, length: Int): IO[Int] =
      readHandle.pollReadRec(()) { _ =>
        IO(guard(unistd.read(readFd, buf.atUnsafe(offset), length.toCSize)))
      }

    def write(buf: Array[Byte], offset: Int, length: Int): IO[Int] =
      writeHandle.pollWriteRec(()) { _ =>
        IO(guard(unistd.write(writeFd, buf.atUnsafe(offset), length.toCSize)))
      }

    def readN(buf: Array[Byte]): IO[Unit] = {
      def go(offset: Int): IO[Unit] =
        if (offset >= buf.length) IO.unit
        else read(buf, offset, buf.length - offset).flatMap(n => go(offset + n))
      go(0)
    }

    def writeAll(buf: Array[Byte]): IO[Unit] = {
      def go(offset: Int): IO[Unit] =
        if (offset >= buf.length) IO.unit
        else write(buf, offset, buf.length - offset).flatMap(n => go(offset + n))
      go(0)
    }
  }

  private def guard(thunk: => CInt): Either[Unit, CInt] = {
    val rtn = thunk
    if (rtn < 0) {
      val en = errno
      if (en == EAGAIN || en == EWOULDBLOCK) Left(())
      else throw new IOException(fromCString(strerror(en)))
    } else Right(rtn)
  }

  private def mkPipe: Resource[IO, Pipe] =
    pipeHandle.flatMap {
      case (readFd, writeFd) =>
        Resource.eval(FileDescriptorPoller.get).flatMap { poller =>
          (
            poller.registerFileDescriptor(readFd, true, false),
            poller.registerFileDescriptor(writeFd, false, true)
          ).mapN(new Pipe(readFd, writeFd, _, _))
        }
    }

  private def mkReadOnlyPipe: Resource[IO, (Int, Int, FileDescriptorPollHandle)] =
    pipeHandle.flatMap {
      case (readFd, writeFd) =>
        Resource.eval(FileDescriptorPoller.get).flatMap { poller =>
          poller.registerFileDescriptor(readFd, true, false).map { readHandle =>
            (readFd, writeFd, readHandle)
          }
        }
    }

  private def pipeHandle: Resource[IO, (Int, Int)] =
    Resource
      .make {
        IO {
          val fd = stackalloc[CInt](2)
          if (unistd.pipe(fd) != 0)
            throw new IOException(fromCString(strerror(errno)))
          (fd(0), fd(1))
        }
      } {
        case (readFd, writeFd) =>
          IO {
            unistd.close(readFd)
            unistd.close(writeFd)
            ()
          }
      }
      .evalTap {
        case (readFd, writeFd) =>
          IO {
            if (fcntl(readFd, F_SETFL, O_NONBLOCK) != 0)
              throw new IOException(fromCString(strerror(errno)))
            if (fcntl(writeFd, F_SETFL, O_NONBLOCK) != 0)
              throw new IOException(fromCString(strerror(errno)))
          }
      }
}
