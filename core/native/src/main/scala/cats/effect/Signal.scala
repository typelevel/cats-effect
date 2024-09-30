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

import cats.syntax.all._

import scala.scalanative.libc.errno._
import scala.scalanative.meta.LinktimeInfo._
import scala.scalanative.posix.errno._
import scala.scalanative.posix.fcntl._
import scala.scalanative.posix.signal._
import scala.scalanative.posix.signalOps._
import scala.scalanative.posix.string._
import scala.scalanative.posix.unistd._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import java.io.IOException

private object Signal {

  private[this] def mkPipe() = if (isLinux || isMac) {
    val fd = stackalloc[CInt](2.toULong)
    if (pipe(fd) != 0)
      throw new IOException(fromCString(strerror(errno)))

    val readFd = fd(0)
    val writeFd = fd(1)

    if (fcntl(readFd, F_SETFL, O_NONBLOCK) != 0)
      throw new IOException(fromCString(strerror(errno)))

    if (fcntl(writeFd, F_SETFL, O_NONBLOCK) != 0)
      throw new IOException(fromCString(strerror(errno)))

    Array(readFd, writeFd)
  } else Array(0, 0)

  private[this] val interruptFds = mkPipe()
  private[this] val interruptReadFd = interruptFds(0)
  private[this] val interruptWriteFd = interruptFds(1)

  private[this] def onInterrupt(signum: CInt): Unit = {
    val _ = signum
    val buf = stackalloc[Byte]()
    write(interruptWriteFd, buf, 1.toULong)
    ()
  }

  private[this] val termFds = mkPipe()
  private[this] val termReadFd = termFds(0)
  private[this] val termWriteFd = termFds(1)

  private[this] def onTerm(signum: CInt): Unit = {
    val _ = signum
    val buf = stackalloc[Byte]()
    write(termWriteFd, buf, 1.toULong)
    ()
  }

  private[this] val dumpFds = mkPipe()
  private[this] val dumpReadFd = dumpFds(0)
  private[this] val dumpWriteFd = dumpFds(1)

  private[this] def onDump(signum: CInt): Unit = {
    val _ = signum
    val buf = stackalloc[Byte]()
    write(dumpWriteFd, buf, 1.toULong)
    ()
  }

  private[this] def installHandler(signum: CInt, handler: CFuncPtr1[CInt, Unit]): Unit = {
    val action = stackalloc[sigaction]()
    action.sa_handler = handler
    sigaddset(action.at2, 13) // mask SIGPIPE
    if (sigaction(signum, action, null) != 0)
      throw new IOException(fromCString(strerror(errno)))
  }

  private[this] final val SIGINT = 2
  private[this] final val SIGTERM = 15
  private[this] final val SIGUSR1 = if (isLinux) 10 else if (isMac) 30 else 0
  private[this] final val SIGINFO = 29

  if (isLinux || isMac) {
    installHandler(SIGINT, onInterrupt(_))
    installHandler(SIGTERM, onTerm(_))
    installHandler(SIGUSR1, onDump(_))
    if (isMac) installHandler(SIGINFO, onDump(_))
  }

  def awaitInterrupt(poller: FileDescriptorPoller): IO[Unit] =
    registerAndAwaitSignal(poller, interruptReadFd)

  def awaitTerm(poller: FileDescriptorPoller): IO[Unit] =
    registerAndAwaitSignal(poller, termReadFd)

  def foreachDump(poller: FileDescriptorPoller, action: IO[Unit]): IO[Nothing] =
    poller.registerFileDescriptor(dumpReadFd, true, false).use { handle =>
      (awaitSignal(handle, dumpReadFd) *> action).foreverM
    }

  private[this] def registerAndAwaitSignal(poller: FileDescriptorPoller, fd: Int): IO[Unit] =
    poller.registerFileDescriptor(fd, true, false).use(awaitSignal(_, fd))

  private[this] def awaitSignal(handle: FileDescriptorPollHandle, fd: Int): IO[Unit] =
    handle.pollReadRec(()) { _ =>
      IO {
        val buf = stackalloc[Byte]()
        val rtn = read(fd, buf, 1.toULong)
        if (rtn >= 0) Either.unit
        else if (errno == EAGAIN) Left(())
        else throw new IOException(fromCString(strerror(errno)))
      }
    }

}
