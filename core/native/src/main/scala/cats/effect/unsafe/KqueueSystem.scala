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

import cats.effect.std.Mutex
import cats.effect.unsafe.metrics.PollerMetrics
import cats.syntax.all._

import org.typelevel.scalaccompat.annotation._

import scala.collection.concurrent.TrieMap
import scala.scalanative.posix.errno._
import scala.scalanative.posix.string._
import scala.scalanative.posix.time._
import scala.scalanative.posix.timeOps._
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import java.io.IOException

object KqueueSystem extends PollingSystem {

  import event._
  import eventImplicits._

  private final val MaxEvents = 64

  type Api = Kqueue

  def close(): Unit = ()

  def makeApi(ctx: PollingContext[Poller]): Kqueue =
    new Kqueue(ctx)

  def makePoller(): Poller = {
    val fd = kqueue()
    if (fd == -1)
      throw new IOException(fromCString(strerror(errno)))
    new Poller(fd)
  }

  def closePoller(poller: Poller): Unit = poller.close()

  def poll(poller: Poller, nanos: Long): PollResult =
    poller.poll(nanos)

  def processReadyEvents(poller: Poller): Boolean =
    poller.processReadyEvents()

  def needsPoll(poller: Poller): Boolean =
    poller.needsPoll()

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit =
    targetPoller.interrupt()

  def metrics(poller: Poller): PollerMetrics = poller.metrics()

  final class Kqueue private[KqueueSystem] (
      ctx: PollingContext[Poller]
  ) extends FileDescriptorPoller {
    def registerFileDescriptor(
        fd: Int,
        reads: Boolean,
        writes: Boolean
    ): Resource[IO, FileDescriptorPollHandle] =
      Resource.eval {
        (Mutex[IO], Mutex[IO]).mapN {
          new PollHandle(fd, _, _)
        }
      }

    def awaitEvent(
        ident: Int,
        filter: Short,
        flags: Short,
        fflags: Int
    ): IO[Long] =
      IO.async[Long] { cb =>
        IO.async_[Option[IO[Unit]]] { cancelCb =>
          ctx.accessPoller { kq =>
            kq.evSet(ident, filter, fflags.toUInt, flags.toUShort, cb)
            cancelCb(Right(Some(IO(kq.removeCallback(ident, filter)))))
          }
        }
      }

    private final class PollHandle(
        fd: Int,
        readMutex: Mutex[IO],
        writeMutex: Mutex[IO]
    ) extends FileDescriptorPollHandle {

      def pollReadRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
        readMutex.lock.surround {
          a.tailRecM { a =>
            f(a).flatTap { r =>
              if (r.isRight)
                IO.unit
              else
                awaitEvent(fd, EVFILT_READ, EV_ADD, 0)
            }
          }
        }

      def pollWriteRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
        writeMutex.lock.surround {
          a.tailRecM { a =>
            f(a).flatTap { r =>
              if (r.isRight)
                IO.unit
              else
                awaitEvent(fd, EVFILT_WRITE, EV_ADD, 0)
            }
          }
        }

    }
  }

  // A kevent is identified by the (ident, filter) pair; there may only be one unique kevent per kqueue
  @inline private def encodeKevent(ident: Int, filter: Short): Long =
    (filter.toLong << 32) | ident.toLong

  final class Poller private[KqueueSystem] (kqfd: Int) extends PollerMetrics {

    private[this] val buffer = new Array[Byte](MaxEvents * sizeof_kevent64_s)
    @inline private[this] def eventlist =
      buffer.atUnsafe(0).asInstanceOf[Ptr[kevent64_s]]
    private[this] var changeCount = 0
    private[this] var readyEventCount = 0

    private[this] val callbacks = new TrieMap[Long, Either[Throwable, Long] => Unit]()

    {
      val event = eventlist

      event.ident = 0.toUInt
      event.filter = EVFILT_USER
      event.flags = (EV_ADD | EV_CLEAR).toUShort
      event.fflags = 0.toUInt

      val rtn = immediate.kevent64(kqfd, event, 1, null, 0, KEVENT_FLAG_IMMEDIATE.toUInt, null)
      if (rtn < 0) throw new IOException(fromCString(strerror(errno)))
    }

    private[KqueueSystem] def evSet(
        ident: Int,
        filter: Short,
        fflags: CUnsignedInt,
        flags: CUnsignedShort,
        cb: Either[Throwable, Long] => Unit
    ): Unit = {
      val event = eventlist + changeCount.toLong

      event.ident = ident.toUSize
      event.filter = filter
      event.fflags = fflags
      event.flags = (flags.toInt | EV_ONESHOT).toUShort

      callbacks.update(encodeKevent(ident, filter), cb)
      incrementOperationCount(filter)

      changeCount += 1
    }

    private[this] var totalReadSubmitted = 0L
    private[this] var totalReadSucceeded = 0L
    private[this] var totalReadErrored = 0L
    private[this] var totalReadCanceled = 0L
    private[this] var readOutstanding = 0

    private[this] var totalWriteSubmitted = 0L
    private[this] var totalWriteSucceeded = 0L
    private[this] var totalWriteErrored = 0L
    private[this] var totalWriteCanceled = 0L
    private[this] var writeOutstanding = 0

    override def operationsOutstandingCount(): Int = readOutstanding + writeOutstanding
    override def totalOperationsSubmittedCount(): Long =
      totalReadSubmitted + totalWriteSubmitted
    override def totalOperationsSucceededCount(): Long =
      totalReadSucceeded + totalWriteSucceeded
    override def totalOperationsErroredCount(): Long = totalReadErrored + totalWriteErrored
    override def totalOperationsCanceledCount(): Long = totalReadCanceled + totalWriteCanceled
    override def acceptOperationsOutstandingCount(): Int = 0
    override def totalAcceptOperationsSubmittedCount(): Long = 0L
    override def totalAcceptOperationsSucceededCount(): Long = 0L
    override def totalAcceptOperationsErroredCount(): Long = 0L
    override def totalAcceptOperationsCanceledCount(): Long = 0L
    override def connectOperationsOutstandingCount(): Int = 0
    override def totalConnectOperationsSubmittedCount(): Long = 0L
    override def totalConnectOperationsSucceededCount(): Long = 0L
    override def totalConnectOperationsErroredCount(): Long = 0L
    override def totalConnectOperationsCanceledCount(): Long = 0L
    override def readOperationsOutstandingCount(): Int = readOutstanding
    override def totalReadOperationsSubmittedCount(): Long = totalReadSubmitted
    override def totalReadOperationsSucceededCount(): Long = totalReadSucceeded
    override def totalReadOperationsErroredCount(): Long = totalReadErrored
    override def totalReadOperationsCanceledCount(): Long = totalReadCanceled
    override def writeOperationsOutstandingCount(): Int = writeOutstanding
    override def totalWriteOperationsSubmittedCount(): Long = totalWriteSubmitted
    override def totalWriteOperationsSucceededCount(): Long = totalWriteSucceeded
    override def totalWriteOperationsErroredCount(): Long = totalWriteErrored
    override def totalWriteOperationsCanceledCount(): Long = totalWriteCanceled

    override def toString: String = "Kqueue"

    private[KqueueSystem] def metrics(): PollerMetrics = this

    private[this] def incrementOperationCount(filter: Short): Unit = {
      if (filter == EVFILT_READ) {
        totalReadSubmitted += 1
        readOutstanding += 1
      }

      if (filter == EVFILT_WRITE) {
        totalWriteSubmitted += 1
        writeOutstanding += 1
      }
    }

    private[this] def handleOperationCompletion(filter: Short, succeeded: Boolean): Unit = {
      if (filter == EVFILT_READ) {
        readOutstanding -= 1
        if (succeeded) totalReadSucceeded += 1 else totalReadErrored += 1
      }

      if (filter == EVFILT_WRITE) {
        writeOutstanding -= 1
        if (succeeded) totalWriteSucceeded += 1 else totalWriteErrored += 1
      }
    }

    private[this] def handleOperationCanceled(filter: Short): Unit = {
      if (filter == EVFILT_READ) {
        totalReadCanceled += 1
        readOutstanding -= 1
      }

      if (filter == EVFILT_WRITE) {
        totalWriteCanceled += 1
        writeOutstanding -= 1
      }
    }

    private[KqueueSystem] def removeCallback(ident: Int, filter: Short): Unit = {
      callbacks -= encodeKevent(ident, filter)
      handleOperationCanceled(filter)
    }

    private[KqueueSystem] def close(): Unit =
      if (unistd.close(kqfd) != 0)
        throw new IOException(fromCString(strerror(errno)))

    private[KqueueSystem] def poll(timeout: Long): PollResult = {

      val timeoutSpec =
        if (timeout <= 0) null
        else {
          val ts = stackalloc[timespec](1)
          ts.tv_sec = (timeout / 1000000000).toCSSize
          ts.tv_nsec = (timeout % 1000000000).toCSSize
          ts
        }

      val flags = if (timeout == 0) KEVENT_FLAG_IMMEDIATE else KEVENT_FLAG_NONE

      val rtn =
        if ((flags & KEVENT_FLAG_IMMEDIATE) != 0)
          immediate.kevent64(
            kqfd,
            eventlist,
            changeCount,
            eventlist,
            MaxEvents,
            flags.toUInt,
            null
          )
        else
          awaiting.kevent64(
            kqfd,
            eventlist,
            changeCount,
            eventlist,
            MaxEvents,
            flags.toUInt,
            timeoutSpec
          )
      changeCount = 0

      if (rtn >= 0) {
        readyEventCount = rtn
        if (rtn > 0) {
          if (rtn < MaxEvents) PollResult.Complete else PollResult.Incomplete
        } else PollResult.Interrupted
      } else if (errno == EINTR) { // spurious wake-up by signal
        PollResult.Interrupted
      } else {
        throw new IOException(fromCString(strerror(errno)))
      }
    }

    private[KqueueSystem] def processReadyEvents(): Boolean = {
      var fibersRescheduled = false
      var i = 0
      var event = eventlist
      while (i < readyEventCount) {
        val cb =
          if (event.filter == EVFILT_USER)
            null // we just ignore the interrupt, since its whole purpose is to awaken the poller
          else {
            val kevent = encodeKevent(event.ident.toInt, event.filter)
            val cb = callbacks.getOrElse(kevent, null)
            callbacks -= kevent
            cb
          }

        if (cb ne null) {
          val succeeded = (event.flags.toLong & EV_ERROR) == 0
          handleOperationCompletion(event.filter, succeeded)
          cb(
            if (!succeeded)
              Left(new IOException(fromCString(strerror(event.data.toInt))))
            else Right(event.data.toLong)
          )
          fibersRescheduled = true
        }

        i += 1
        event += 1
      }

      readyEventCount = 0
      fibersRescheduled
    }

    private[KqueueSystem] def needsPoll(): Boolean = changeCount > 0 || callbacks.nonEmpty

    private[KqueueSystem] def interrupt(): Unit = {
      val event = stackalloc[Byte](sizeof_kevent64_s).asInstanceOf[Ptr[kevent64_s]]
      event.ident = 0.toUInt
      event.filter = EVFILT_USER
      event.flags = 0.toUShort
      event.fflags = NOTE_TRIGGER.toUInt

      val rtn = immediate.kevent64(kqfd, event, 1, null, 0, KEVENT_FLAG_IMMEDIATE.toUInt, null)
      if (rtn < 0) throw new IOException(fromCString(strerror(errno)))
    }
  }

  @nowarn212
  @extern
  private object event {
    // Derived from https://opensource.apple.com/source/xnu/xnu-7195.81.3/bsd/sys/event.h.auto.html

    final val EVFILT_READ = -1
    final val EVFILT_WRITE = -2
    final val EVFILT_USER = -10

    final val KEVENT_FLAG_NONE = 0x000000
    final val KEVENT_FLAG_IMMEDIATE = 0x000001

    final val EV_ADD = 0x0001
    final val EV_DELETE = 0x0002
    final val EV_ONESHOT = 0x0010
    final val EV_CLEAR = 0x0020
    final val EV_ERROR = 0x4000

    final val NOTE_TRIGGER = 0x01000000

    type kevent64_s
    final val sizeof_kevent64_s = 48

    def kqueue(): CInt = extern

    @extern
    object awaiting {

      @blocking
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

    @extern
    object immediate {

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

      def fflags: CUnsignedInt = !(kevent64_s.asInstanceOf[Ptr[CUnsignedInt]] + 3)
      def fflags_=(fflags: CUnsignedInt): Unit =
        !(kevent64_s.asInstanceOf[Ptr[CUnsignedInt]] + 3) = fflags

      def data: CLong = !(kevent64_s.asInstanceOf[Ptr[CLong]] + 2)

      def udata: Ptr[Byte] = !(kevent64_s.asInstanceOf[Ptr[Ptr[Byte]]] + 3)
      def udata_=(udata: Ptr[Byte]): Unit =
        !(kevent64_s.asInstanceOf[Ptr[Ptr[Byte]]] + 3) = udata
    }

    implicit val kevent64_sTag: Tag[kevent64_s] =
      Tag.materializeCArrayTag[Byte, Nat.Digit2[Nat._4, Nat._8]].asInstanceOf[Tag[kevent64_s]]
  }
}
