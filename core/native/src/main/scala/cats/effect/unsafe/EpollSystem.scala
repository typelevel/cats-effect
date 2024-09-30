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
package unsafe

import cats.effect.std.Mutex
import cats.syntax.all._

import org.typelevel.scalaccompat.annotation._

import scala.annotation.tailrec
import scala.scalanative.annotation.alwaysinline
import scala.scalanative.libc.errno._
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno._
import scala.scalanative.posix.string._
import scala.scalanative.posix.unistd
import scala.scalanative.runtime._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import java.io.IOException
import java.util.{Collections, IdentityHashMap, Set}

object EpollSystem extends PollingSystem {

  import epoll._
  import epollImplicits._

  private[this] final val MaxEvents = 64

  type Api = FileDescriptorPoller

  def close(): Unit = ()

  def makeApi(access: (Poller => Unit) => Unit): Api =
    new FileDescriptorPollerImpl(access)

  def makePoller(): Poller = {
    val fd = epoll_create1(0)
    if (fd == -1)
      throw new IOException(fromCString(strerror(errno)))
    new Poller(fd)
  }

  def closePoller(poller: Poller): Unit = poller.close()

  def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean =
    poller.poll(nanos)

  def needsPoll(poller: Poller): Boolean = poller.needsPoll()

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ()

  private final class FileDescriptorPollerImpl private[EpollSystem] (
      access: (Poller => Unit) => Unit)
      extends FileDescriptorPoller {

    def registerFileDescriptor(
        fd: Int,
        reads: Boolean,
        writes: Boolean
    ): Resource[IO, FileDescriptorPollHandle] =
      Resource {
        (Mutex[IO], Mutex[IO]).flatMapN { (readMutex, writeMutex) =>
          IO.async_[(PollHandle, IO[Unit])] { cb =>
            access { epoll =>
              val handle = new PollHandle(readMutex, writeMutex)
              epoll.register(fd, reads, writes, handle, cb)
            }
          }
        }
      }

  }

  private final class PollHandle(
      readMutex: Mutex[IO],
      writeMutex: Mutex[IO]
  ) extends FileDescriptorPollHandle {

    private[this] var readReadyCounter = 0
    private[this] var readCallback: Either[Throwable, Int] => Unit = null

    private[this] var writeReadyCounter = 0
    private[this] var writeCallback: Either[Throwable, Int] => Unit = null

    def notify(events: Int): Unit = {
      if ((events & EPOLLIN) != 0) {
        val counter = readReadyCounter + 1
        readReadyCounter = counter
        val cb = readCallback
        readCallback = null
        if (cb ne null) cb(Right(counter))
      }
      if ((events & EPOLLOUT) != 0) {
        val counter = writeReadyCounter + 1
        writeReadyCounter = counter
        val cb = writeCallback
        writeCallback = null
        if (cb ne null) cb(Right(counter))
      }
    }

    def pollReadRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
      readMutex.lock.surround {
        def go(a: A, before: Int): IO[B] =
          f(a).flatMap {
            case Left(a) =>
              IO(readReadyCounter).flatMap { after =>
                if (before != after)
                  // there was a read-ready notification since we started, try again immediately
                  go(a, after)
                else
                  IO.asyncCheckAttempt[Int] { cb =>
                    IO {
                      readCallback = cb
                      // check again before we suspend
                      val now = readReadyCounter
                      if (now != before) {
                        readCallback = null
                        Right(now)
                      } else Left(Some(IO(this.readCallback = null)))
                    }
                  }.flatMap(go(a, _))
              }
            case Right(b) => IO.pure(b)
          }

        IO(readReadyCounter).flatMap(go(a, _))
      }

    def pollWriteRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
      writeMutex.lock.surround {
        def go(a: A, before: Int): IO[B] =
          f(a).flatMap {
            case Left(a) =>
              IO(writeReadyCounter).flatMap { after =>
                if (before != after)
                  // there was a write-ready notification since we started, try again immediately
                  go(a, after)
                else
                  IO.asyncCheckAttempt[Int] { cb =>
                    IO {
                      writeCallback = cb
                      // check again before we suspend
                      val now = writeReadyCounter
                      if (now != before) {
                        writeCallback = null
                        Right(now)
                      } else Left(Some(IO(this.writeCallback = null)))
                    }
                  }.flatMap(go(a, _))
              }
            case Right(b) => IO.pure(b)
          }

        IO(writeReadyCounter).flatMap(go(a, _))
      }

  }

  final class Poller private[EpollSystem] (epfd: Int) {

    private[this] val handles: Set[PollHandle] =
      Collections.newSetFromMap(new IdentityHashMap)

    private[EpollSystem] def close(): Unit =
      if (unistd.close(epfd) != 0)
        throw new IOException(fromCString(strerror(errno)))

    private[EpollSystem] def poll(timeout: Long): Boolean = {

      val events = stackalloc[epoll_event](MaxEvents.toULong)
      var polled = false

      @tailrec
      def processEvents(timeout: Int): Unit = {

        val triggeredEvents = epoll_wait(epfd, events, MaxEvents, timeout)

        if (triggeredEvents >= 0) {
          polled = true

          var i = 0
          while (i < triggeredEvents) {
            val event = events + i.toLong
            val handle = fromPtr(event.data)
            handle.notify(event.events.toInt)
            i += 1
          }
        } else if (errno != EINTR) { // spurious wake-up by signal
          throw new IOException(fromCString(strerror(errno)))
        }

        if (triggeredEvents >= MaxEvents)
          processEvents(0) // drain the ready list
        else
          ()
      }

      val timeoutMillis = if (timeout == -1) -1 else (timeout / 1000000).toInt
      processEvents(timeoutMillis)

      polled
    }

    private[EpollSystem] def needsPoll(): Boolean = !handles.isEmpty()

    private[EpollSystem] def register(
        fd: Int,
        reads: Boolean,
        writes: Boolean,
        handle: PollHandle,
        cb: Either[Throwable, (PollHandle, IO[Unit])] => Unit
    ): Unit = {
      val event = stackalloc[epoll_event]()
      event.events =
        (EPOLLET | (if (reads) EPOLLIN else 0) | (if (writes) EPOLLOUT else 0)).toUInt
      event.data = toPtr(handle)

      val result =
        if (epoll_ctl(epfd, EPOLL_CTL_ADD, fd, event) != 0)
          Left(new IOException(fromCString(strerror(errno))))
        else {
          handles.add(handle)
          val remove = IO {
            handles.remove(handle)
            if (epoll_ctl(epfd, EPOLL_CTL_DEL, fd, null) != 0)
              throw new IOException(fromCString(strerror(errno)))
          }
          Right((handle, remove))
        }

      cb(result)
    }

    @alwaysinline private[this] def toPtr(handle: PollHandle): Ptr[Byte] =
      fromRawPtr(Intrinsics.castObjectToRawPtr(handle))

    @alwaysinline private[this] def fromPtr[A](ptr: Ptr[Byte]): PollHandle =
      Intrinsics.castRawPtrToObject(toRawPtr(ptr)).asInstanceOf[PollHandle]
  }

  @nowarn212
  @extern
  private object epoll {

    final val EPOLL_CTL_ADD = 1
    final val EPOLL_CTL_DEL = 2
    final val EPOLL_CTL_MOD = 3

    final val EPOLLIN = 0x001
    final val EPOLLOUT = 0x004
    final val EPOLLONESHOT = 1 << 30
    final val EPOLLET = 1 << 31

    type epoll_event
    type epoll_data_t = Ptr[Byte]

    def epoll_create1(flags: Int): Int = extern

    def epoll_ctl(epfd: Int, op: Int, fd: Int, event: Ptr[epoll_event]): Int = extern

    def epoll_wait(epfd: Int, events: Ptr[epoll_event], maxevents: Int, timeout: Int): Int =
      extern

  }

  private object epollImplicits {

    implicit final class epoll_eventOps(epoll_event: Ptr[epoll_event]) {
      def events: CUnsignedInt = !epoll_event.asInstanceOf[Ptr[CUnsignedInt]]
      def events_=(events: CUnsignedInt): Unit =
        !epoll_event.asInstanceOf[Ptr[CUnsignedInt]] = events

      def data: epoll_data_t = {
        val offset =
          if (LinktimeInfo.target.arch == "x86_64")
            sizeof[CUnsignedInt]
          else
            sizeof[Ptr[Byte]]
        !(epoll_event.asInstanceOf[Ptr[Byte]] + offset).asInstanceOf[Ptr[epoll_data_t]]
      }

      def data_=(data: epoll_data_t): Unit = {
        val offset =
          if (LinktimeInfo.target.arch == "x86_64")
            sizeof[CUnsignedInt]
          else
            sizeof[Ptr[Byte]]
        !(epoll_event.asInstanceOf[Ptr[Byte]] + offset).asInstanceOf[Ptr[epoll_data_t]] = data
      }
    }

    implicit val epoll_eventTag: Tag[epoll_event] =
      if (LinktimeInfo.target.arch == "x86_64")
        Tag
          .materializeCArrayTag[Byte, Nat.Digit2[Nat._1, Nat._2]]
          .asInstanceOf[Tag[epoll_event]]
      else
        Tag
          .materializeCArrayTag[Byte, Nat.Digit2[Nat._1, Nat._6]]
          .asInstanceOf[Tag[epoll_event]]
  }
}
