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

import scala.collection.mutable.LongMap
import scala.scalanative.libc.errno._
import scala.scalanative.posix.string._
import scala.scalanative.posix.time._
import scala.scalanative.posix.timeOps._
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import scala.util.control.NonFatal

import java.io.IOException
import java.util.ArrayDeque

import KqueueSystem.EvAdd
import KqueueSystem.event._
import KqueueSystem.eventImplicits._

final class KqueueSystem private (maxEvents: Int) extends PollingSystem {

  def makePoller(): Poller = {
    val fd = kqueue()
    if (fd == -1)
      throw new IOException(fromCString(strerror(errno)))
    new Poller(fd, maxEvents)
  }

  def close(poller: Poller): Unit = poller.close()

  def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean =
    poller.poll(nanos, reportFailure)

  final class Poller private[KqueueSystem] (private[KqueueSystem] val kqfd: Int, maxEvents: Int)
      extends FileDescriptorPoller {

    private[this] val changes: ArrayDeque[EvAdd] = new ArrayDeque
    private[this] val callbacks: LongMap[FileDescriptorPoller.Callback] = new LongMap

    private[KqueueSystem] def close(): Unit =
      if (unistd.close(kqfd) != 0)
        throw new IOException(fromCString(strerror(errno)))

    private[KqueueSystem] def poll(timeout: Long, reportFailure: Throwable => Unit): Boolean = {
      val noCallbacks = callbacks.isEmpty

      // pre-process the changes to filter canceled ones
      val changelist = stackalloc[kevent64_s](changes.size().toLong)
      var change = changelist
      var changeCount = 0
      while (!changes.isEmpty()) {
        val evAdd = changes.poll()
        if (!evAdd.canceled) {
          change.ident = evAdd.fd.toULong
          change.filter = evAdd.filter
          change.flags = (EV_ADD | EV_CLEAR).toUShort
          change.udata = FileDescriptorPoller.Callback.toPtr(evAdd.cb)
          change += 1
          changeCount += 1
        }
      }

      if (timeout <= 0 && noCallbacks && changeCount == 0)
        false // nothing to do here
      else {

        val timeoutSpec =
          if (timeout <= 0) null
          else {
            val ts = stackalloc[timespec]()
            ts.tv_sec = timeout / 1000000000
            ts.tv_nsec = timeout % 1000000000
            ts
          }

        val eventlist = stackalloc[kevent64_s](maxEvents.toLong)
        val flags = (if (timeout == 0) KEVENT_FLAG_IMMEDIATE else KEVENT_FLAG_NONE).toUInt
        val triggeredEvents =
          kevent64(kqfd, changelist, changeCount, eventlist, maxEvents, flags, timeoutSpec)

        if (triggeredEvents >= 0) {
          var i = 0
          var event = eventlist
          while (i < triggeredEvents) {
            if ((event.flags.toLong & EV_ERROR) != 0) {

              // TODO it would be interesting to propagate this failure via the callback
              reportFailure(
                new RuntimeException(
                  s"kevent64: flags=${event.flags.toHexString} errno=${event.data}"
                )
              )

            } else if (callbacks.contains(event.ident.toLong)) {
              val filter = event.filter
              val cb = FileDescriptorPoller.Callback.fromPtr(event.udata)

              try {
                cb.notifyFileDescriptorEvents(filter == EVFILT_READ, filter == EVFILT_WRITE)
              } catch {
                case NonFatal(ex) =>
                  reportFailure(ex)
              }
            }

            i += 1
            event += 1
          }
        } else {
          throw new IOException(fromCString(strerror(errno)))
        }

        !changes.isEmpty() || callbacks.nonEmpty
      }
    }

    def registerFileDescriptor(fd: Int, reads: Boolean, writes: Boolean)(
        cb: FileDescriptorPoller.Callback): Runnable = {

      val readEvent =
        if (reads)
          new EvAdd(fd, EVFILT_READ, cb)
        else null

      val writeEvent =
        if (writes)
          new EvAdd(fd, EVFILT_WRITE, cb)
        else null

      if (readEvent != null)
        changes.add(readEvent)
      if (writeEvent != null)
        changes.add(writeEvent)

      callbacks(fd.toLong) = cb

      () => {
        // we do not need to explicitly unregister the fd with the kqueue,
        // b/c it will be unregistered automatically when the fd is closed

        // release the callback, so it can be GCed
        callbacks.remove(fd.toLong)

        // cancel the events, such that if they are currently pending in the
        // changes queue awaiting registration, they will not be registered
        if (readEvent != null) readEvent.cancel()
        if (writeEvent != null) writeEvent.cancel()
      }
    }

  }

}

object KqueueSystem {
  def apply(maxEvents: Int): KqueueSystem = new KqueueSystem(maxEvents)

  private final class EvAdd(
      val fd: Int,
      val filter: Short,
      val cb: FileDescriptorPoller.Callback
  ) {
    var canceled = false
    def cancel() = canceled = true
  }

  @extern
  private[unsafe] object event {
    // Derived from https://opensource.apple.com/source/xnu/xnu-7195.81.3/bsd/sys/event.h.auto.html

    final val EVFILT_READ = -1
    final val EVFILT_WRITE = -2

    final val KEVENT_FLAG_NONE = 0x000000
    final val KEVENT_FLAG_IMMEDIATE = 0x000001

    final val EV_ADD = 0x0001
    final val EV_DELETE = 0x0002
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

  private[unsafe] object eventImplicits {

    implicit final class kevent64_sOps(kevent64_s: Ptr[kevent64_s]) {
      def ident: CUnsignedLongInt = !(kevent64_s.asInstanceOf[Ptr[CUnsignedLongInt]])
      def ident_=(ident: CUnsignedLongInt): Unit =
        !(kevent64_s.asInstanceOf[Ptr[CUnsignedLongInt]]) = ident

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
