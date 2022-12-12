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

package cats.effect
package unsafe

import cats.effect.std.{CountDownLatch, Dispatcher, Queue}
import cats.syntax.all._

import scala.scalanative.libc.errno._
import scala.scalanative.posix.string._
import scala.scalanative.posix.unistd._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import java.io.IOException

class FileDescriptorPollerSpec extends BaseSpec {

  case class Pipe(readFd: Int, writeFd: Int)

  def mkPipe: Resource[IO, Pipe] =
    Resource.make {
      IO {
        val fd = stackalloc[CInt](2)
        if (pipe(fd) != 0)
          throw new IOException(fromCString(strerror(errno)))
        else
          Pipe(fd(0), fd(1))
      }
    } {
      case Pipe(readFd, writeFd) =>
        IO {
          close(readFd)
          close(writeFd)
          ()
        }
    }

  def onRead(loop: EventLoop[FileDescriptorPoller], fd: Int, cb: IO[Unit]): Resource[IO, Unit] =
    Dispatcher
      .sequential[IO]
      .flatMap { dispatcher =>
        Resource.make {
          IO {
            loop.poller().registerFileDescriptor(fd, true, false) { (readReady, _) =>
              dispatcher.unsafeRunAndForget(cb.whenA(readReady))
            }
          }
        }(unregister => IO(unregister.run()))
      }
      .void

  "FileDescriptorPoller" should {

    "notify read-ready events" in real {
      mkPipe.use {
        case Pipe(readFd, writeFd) =>
          IO.eventLoop[FileDescriptorPoller].map(_.get).flatMap { loop =>
            Queue.unbounded[IO, Unit].flatMap { queue =>
              onRead(loop, readFd, queue.offer(())).surround {
                for {
                  buf <- IO(new Array[Byte](4))
                  _ <- IO(write(writeFd, Array[Byte](1, 2, 3).at(0), 3.toULong))
                    .background
                    .surround(queue.take *> IO(read(readFd, buf.at(0), 3.toULong)))
                  _ <- IO(write(writeFd, Array[Byte](42).at(0), 1.toULong))
                    .background
                    .surround(queue.take *> IO(read(readFd, buf.at(3), 1.toULong)))
                } yield buf.toList must be_==(List[Byte](1, 2, 3, 42))
              }
            }
          }
      }
    }

    "handle lots of simultaneous events" in real {
      mkPipe.replicateA(1000).use { pipes =>
        IO.eventLoop[FileDescriptorPoller].map(_.get).flatMap { loop =>
          CountDownLatch[IO](1000).flatMap { latch =>
            pipes.traverse_(pipe => onRead(loop, pipe.readFd, latch.release)).surround {
              IO { // trigger all the pipes at once
                pipes.foreach(pipe => write(pipe.writeFd, Array[Byte](42).at(0), 1.toULong))
              }.background.surround(latch.await.as(true))
            }
          }
        }
      }
    }

    "notify of pre-existing readiness on registration" in real {
      mkPipe.use {
        case Pipe(readFd, writeFd) =>
          IO.eventLoop[FileDescriptorPoller].map(_.get).flatMap { loop =>
            val registerAndWait = IO.deferred[Unit].flatMap { gate =>
              onRead(loop, readFd, gate.complete(()).void).surround(gate.get)
            }

            IO(write(writeFd, Array[Byte](42).at(0), 1.toULong)) *>
              registerAndWait *> registerAndWait *> IO.pure(true)
          }
      }
    }
  }

}
