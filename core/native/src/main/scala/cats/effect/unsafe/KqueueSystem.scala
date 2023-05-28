/*
 * Copyright 2020-2023 Typelevel
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

import cats.effect.std.Semaphore
import cats.syntax.all._

import org.typelevel.scalaccompat.annotation._

import scala.annotation.tailrec
import scala.scalanative.libc.errno._
import scala.scalanative.posix.string._
import scala.scalanative.posix.time._
import scala.scalanative.posix.timeOps._
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import java.io.IOException
import java.util.HashMap

object KqueueSystem extends PollingSystem {

  import event._
  import eventImplicits._

  private final val MaxEvents = 64

  type Api = FileDescriptorPoller

  def makeApi(register: (Poller => Unit) => Unit): FileDescriptorPoller =
    new FileDescriptorPollerImpl(register)

  def makePoller(): Poller = {
    val fd = kqueue()
    if (fd == -1)
      throw new IOException(fromCString(strerror(errno)))
    new Poller(fd)
  }

  def closePoller(poller: Poller): Unit = poller.close()

  def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean =
    poller.poll(nanos)

  def needsPoll(poller: Poller): Boolean =
    poller.needsPoll()

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ()

  private final class FileDescriptorPollerImpl private[KqueueSystem] (
      register: (Poller => Unit) => Unit
  ) extends FileDescriptorPoller {
    def registerFileDescriptor(
        fd: Int,
        reads: Boolean,
        writes: Boolean
    ): Resource[IO, FileDescriptorPollHandle] =
      Resource.eval {
        (Semaphore[IO](1), Semaphore[IO](1)).mapN {
          new PollHandle(register, fd, _, _)
        }
      }
  }

  private final class PollHandle(
      register: (Poller => Unit) => Unit,
      fd: Int,
      readSemaphore: Semaphore[IO],
      writeSemaphore: Semaphore[IO]
  ) extends FileDescriptorPollHandle {

    private[this] val readEvent = KEvent(fd.toLong, EVFILT_READ)
    private[this] val writeEvent = KEvent(fd.toLong, EVFILT_WRITE)

    def pollReadRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
      readSemaphore.permit.surround {
        a.tailRecM { a =>
          f(a).flatTap { r =>
            if (r.isRight)
              IO.unit
            else
              IO.async[Unit] { kqcb =>
                IO.async_[Option[IO[Unit]]] { cb =>
                  register { kqueue =>
                    kqueue.evSet(readEvent, EV_ADD.toUShort, kqcb)
                    cb(Right(Some(IO(kqueue.removeCallback(readEvent)))))
                  }
                }

              }
          }
        }
      }

    def pollWriteRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
      writeSemaphore.permit.surround {
        a.tailRecM { a =>
          f(a).flatTap { r =>
            if (r.isRight)
              IO.unit
            else
              IO.async[Unit] { kqcb =>
                IO.async_[Option[IO[Unit]]] { cb =>
                  register { kqueue =>
                    kqueue.evSet(writeEvent, EV_ADD.toUShort, kqcb)
                    cb(Right(Some(IO(kqueue.removeCallback(writeEvent)))))
                  }
                }
              }
          }
        }
      }

  }

  private final case class KEvent(ident: Long, filter: Short)

  final class Poller private[KqueueSystem] (kqfd: Int) {

    private[this] val changelistArray = new Array[Byte](sizeof[kevent64_s].toInt * MaxEvents)
    private[this] val changelist = changelistArray.at(0).asInstanceOf[Ptr[kevent64_s]]
    private[this] var changeCount = 0

    private[this] val callbacks = new HashMap[KEvent, Either[Throwable, Unit] => Unit]()

    private[KqueueSystem] def evSet(
        event: KEvent,
        flags: CUnsignedShort,
        cb: Either[Throwable, Unit] => Unit
    ): Unit = {
      val change = changelist + changeCount.toLong

      change.ident = event.ident.toULong
      change.filter = event.filter
      change.flags = (flags.toInt | EV_ONESHOT).toUShort

      callbacks.put(event, cb)

      changeCount += 1
    }

    private[KqueueSystem] def removeCallback(event: KEvent): Unit = {
      callbacks.remove(event)
      ()
    }

    private[KqueueSystem] def close(): Unit =
      if (unistd.close(kqfd) != 0)
        throw new IOException(fromCString(strerror(errno)))

    private[KqueueSystem] def poll(timeout: Long): Boolean = {

      val eventlist = stackalloc[kevent64_s](MaxEvents.toLong)
      var polled = false

      @tailrec
      def processEvents(timeout: Ptr[timespec], changeCount: Int, flags: Int): Unit = {

        val triggeredEvents =
          kevent64(
            kqfd,
            changelist,
            changeCount,
            eventlist,
            MaxEvents,
            flags.toUInt,
            timeout
          )

        if (triggeredEvents >= 0) {
          polled = true

          var i = 0
          var event = eventlist
          while (i < triggeredEvents) {
            val cb = callbacks.remove(KEvent(event.ident.toLong, event.filter))

            if (cb ne null)
              cb(
                if ((event.flags.toLong & EV_ERROR) != 0)
                  Left(new IOException(fromCString(strerror(event.data.toInt))))
                else Either.unit
              )

            i += 1
            event += 1
          }
        } else {
          throw new IOException(fromCString(strerror(errno)))
        }

        if (triggeredEvents >= MaxEvents)
          processEvents(null, 0, KEVENT_FLAG_NONE) // drain the ready list
        else
          ()
      }

      val timeoutSpec =
        if (timeout <= 0) null
        else {
          val ts = stackalloc[timespec]()
          ts.tv_sec = timeout / 1000000000
          ts.tv_nsec = timeout % 1000000000
          ts
        }

      val flags = if (timeout == 0) KEVENT_FLAG_IMMEDIATE else KEVENT_FLAG_NONE

      processEvents(timeoutSpec, changeCount, flags)
      changeCount = 0

      polled
    }

    def needsPoll(): Boolean = changeCount > 0 || !callbacks.isEmpty()
  }

  @nowarn212
  @extern
  private object event {
    // Derived from https://opensource.apple.com/source/xnu/xnu-7195.81.3/bsd/sys/event.h.auto.html

    final val EVFILT_READ = -1
    final val EVFILT_WRITE = -2

    final val KEVENT_FLAG_NONE = 0x000000
    final val KEVENT_FLAG_IMMEDIATE = 0x000001

    final val EV_ADD = 0x0001
    final val EV_DELETE = 0x0002
    final val EV_ONESHOT = 0x0010
    final val EV_CLEAR = 0x0020
    final val EV_ERROR = 0x4000

    type kevent64_s

    def kqueue(): CInt = extern

    def kevent64(
        kq: CInt,
        changelist: Ptr[kevent64_s],
        nchanges: CInt,
        eventlist: Ptr[kevent64_s],
        nevents: CInt,
        flags: CUnsignedInt,
        timeout: Ptr[timespec]
    ): CInt = extern

  }

  private object eventImplicits {

    implicit final class kevent64_sOps(kevent64_s: Ptr[kevent64_s]) {
      def ident: CUnsignedLongInt = !kevent64_s.asInstanceOf[Ptr[CUnsignedLongInt]]
      def ident_=(ident: CUnsignedLongInt): Unit =
        !kevent64_s.asInstanceOf[Ptr[CUnsignedLongInt]] = ident

      def filter: CShort = !(kevent64_s.asInstanceOf[Ptr[CShort]] + 4)
      def filter_=(filter: CShort): Unit =
        !(kevent64_s.asInstanceOf[Ptr[CShort]] + 4) = filter

      def flags: CUnsignedShort = !(kevent64_s.asInstanceOf[Ptr[CUnsignedShort]] + 5)
      def flags_=(flags: CUnsignedShort): Unit =
        !(kevent64_s.asInstanceOf[Ptr[CUnsignedShort]] + 5) = flags

      def data: CLong = !(kevent64_s.asInstanceOf[Ptr[CLong]] + 2)

      def udata: Ptr[Byte] = !(kevent64_s.asInstanceOf[Ptr[Ptr[Byte]]] + 3)
      def udata_=(udata: Ptr[Byte]): Unit =
        !(kevent64_s.asInstanceOf[Ptr[Ptr[Byte]]] + 3) = udata
    }

    implicit val kevent64_sTag: Tag[kevent64_s] =
      Tag.materializeCArrayTag[Byte, Nat.Digit2[Nat._4, Nat._8]].asInstanceOf[Tag[kevent64_s]]
  }
}
