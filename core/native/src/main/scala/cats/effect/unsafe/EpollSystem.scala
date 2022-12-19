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

import org.typelevel.scalaccompat.annotation._

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.scalanative.libc.errno._
import scala.scalanative.posix.string._
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import scala.util.control.NonFatal

import java.io.IOException
import java.util.{Collections, IdentityHashMap, Set}

object EpollSystem extends PollingSystem {

  import epoll._
  import epollImplicits._

  private[this] final val MaxEvents = 64

  def makePoller(ec: ExecutionContext, data: () => PollData): Poller = new Poller

  def makePollData(): PollData = {
    val fd = epoll_create1(0)
    if (fd == -1)
      throw new IOException(fromCString(strerror(errno)))
    new PollData(fd)
  }

  def closePollData(data: PollData): Unit = data.close()

  def poll(data: PollData, nanos: Long, reportFailure: Throwable => Unit): Boolean =
    data.poll(nanos, reportFailure)

  final class Poller private[EpollSystem] ()

  final class PollData private[EpollSystem] (epfd: Int) {

    // private[this] val callbacks: Set[FileDescriptorPoller.Callback] =
    //   Collections.newSetFromMap(new IdentityHashMap)

    private[EpollSystem] def close(): Unit =
      if (unistd.close(epfd) != 0)
        throw new IOException(fromCString(strerror(errno)))

    private[EpollSystem] def poll(timeout: Long, reportFailure: Throwable => Unit): Boolean = {
      // val noCallbacks = callbacks.isEmpty()

      // if (timeout <= 0 && noCallbacks)
      //   false // nothing to do here
      // else {
      //   val events = stackalloc[epoll_event](MaxEvents.toLong)

      //   @tailrec
      //   def processEvents(timeout: Int): Unit = {

      //     val triggeredEvents = epoll_wait(epfd, events, MaxEvents, timeout)

      //     if (triggeredEvents >= 0) {
      //       var i = 0
      //       while (i < triggeredEvents) {
      //         val event = events + i.toLong
      //         val cb = FileDescriptorPoller.Callback.fromPtr(event.data)
      //         try {
      //           val e = event.events.toInt
      //           val readReady = (e & EPOLLIN) != 0
      //           val writeReady = (e & EPOLLOUT) != 0
      //           cb.notifyFileDescriptorEvents(readReady, writeReady)
      //         } catch {
      //           case ex if NonFatal(ex) => reportFailure(ex)
      //         }
      //         i += 1
      //       }
      //     } else {
      //       throw new IOException(fromCString(strerror(errno)))
      //     }

      //     if (triggeredEvents >= MaxEvents)
      //       processEvents(0) // drain the ready list
      //     else
      //       ()
      //   }

      //   val timeoutMillis = if (timeout == -1) -1 else (timeout / 1000000).toInt
      //   processEvents(timeoutMillis)

      //   !callbacks.isEmpty()
      // }
      ???
    }

    // def registerFileDescriptor(fd: Int, reads: Boolean, writes: Boolean)(
    //     cb: FileDescriptorPoller.Callback): Runnable = {
    //   val event = stackalloc[epoll_event]()
    //   event.events =
    //     (EPOLLET | (if (reads) EPOLLIN else 0) | (if (writes) EPOLLOUT else 0)).toUInt
    //   event.data = FileDescriptorPoller.Callback.toPtr(cb)

    //   if (epoll_ctl(epfd, EPOLL_CTL_ADD, fd, event) != 0)
    //     throw new IOException(fromCString(strerror(errno)))
    //   callbacks.add(cb)

    //   () => {
    //     callbacks.remove(cb)
    //     if (epoll_ctl(epfd, EPOLL_CTL_DEL, fd, null) != 0)
    //       throw new IOException(fromCString(strerror(errno)))
    //   }
    // }
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
      def events: CUnsignedInt = !(epoll_event.asInstanceOf[Ptr[CUnsignedInt]])
      def events_=(events: CUnsignedInt): Unit =
        !(epoll_event.asInstanceOf[Ptr[CUnsignedInt]]) = events

      def data: epoll_data_t =
        !((epoll_event.asInstanceOf[Ptr[Byte]] + sizeof[CUnsignedInt])
          .asInstanceOf[Ptr[epoll_data_t]])
      def data_=(data: epoll_data_t): Unit =
        !((epoll_event.asInstanceOf[Ptr[Byte]] + sizeof[CUnsignedInt])
          .asInstanceOf[Ptr[epoll_data_t]]) = data
    }

    implicit val epoll_eventTag: Tag[epoll_event] =
      Tag.materializeCArrayTag[Byte, Nat.Digit2[Nat._1, Nat._2]].asInstanceOf[Tag[epoll_event]]

  }
}
