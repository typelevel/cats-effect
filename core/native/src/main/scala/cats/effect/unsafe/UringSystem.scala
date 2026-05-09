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

import cats.effect.kernel.{Cont, MonadCancelThrow}
import cats.effect.std.Mutex
import cats.effect.unsafe.metrics.PollerMetrics
import cats.syntax.all._
import cats.~>

import org.typelevel.scalaccompat.annotation._

import scala.scalanative.libc.stdlib
import scala.scalanative.posix.errno._
import scala.scalanative.posix.string._
import scala.scalanative.posix.unistd
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import java.io.IOException
import java.util.{Collections, IdentityHashMap, Set}
import java.util.concurrent.ConcurrentLinkedDeque

object UringSystem extends PollingSystem {

  import uringNative._
  import uringNativeOps._

  private[this] final val MaxEvents = 64

  type Api = Uring

  def close(): Unit = ()

  def makeApi(ctx: PollingContext[Poller]): Api =
    new UringApi(ctx)

  def makePoller(): Poller = {
    val ring = stdlib.malloc(sizeof[io_uring]).asInstanceOf[Ptr[io_uring]]
    if (ring == null)
      throw new IOException(fromCString(strerror(errno)))

    val flags = IORING_SETUP_SUBMIT_ALL |
      IORING_SETUP_COOP_TASKRUN |
      IORING_SETUP_TASKRUN_FLAG

    val ret = io_uring_queue_init(MaxEvents.toUInt, ring, flags.toUInt)
    if (ret < 0) {
      stdlib.free(ring.asInstanceOf[Ptr[Byte]])
      throw new IOException(fromCString(strerror(-ret)))
    }

    val pipeFds = stackalloc[CInt](2)
    if (unistd.pipe(pipeFds) != 0) {
      val msg = fromCString(strerror(errno))
      io_uring_queue_exit(ring)
      stdlib.free(ring.asInstanceOf[Ptr[Byte]])
      throw new IOException(msg)
    }

    new Poller(ring, pipeFds(0), pipeFds(1))
  }

  def closePoller(poller: Poller): Unit = poller.close()

  def poll(poller: Poller, nanos: Long): PollResult =
    poller.poll(nanos)

  def processReadyEvents(poller: Poller): Boolean =
    poller.processReadyEvents()

  def needsPoll(poller: Poller): Boolean = poller.needsPoll()

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit =
    targetPoller.wakeup()

  def metrics(poller: Poller): PollerMetrics = poller.metrics()

  abstract class Uring private[UringSystem] () {
    def call(prep: Ptr[io_uring_sqe] => Unit): IO[Int]

    def bracket(prep: Ptr[io_uring_sqe] => Unit)(
        release: Int => IO[Unit]
    ): Resource[IO, Int]
  }

  object Uring {
    def get: IO[Uring] =
      IO.pollers.flatMap {
        _.collectFirst { case ring: Uring => ring }
          .liftTo[IO](new RuntimeException("No UringSystem installed"))
      }
  }

  private final class UringApi(ctx: PollingContext[Poller])
      extends Uring
      with FileDescriptorPoller {
    private[this] val noopRelease: Int => IO[Unit] = _ => IO.unit

    def call(prep: Ptr[io_uring_sqe] => Unit): IO[Int] =
      exec(prep)(noopRelease)

    def bracket(prep: Ptr[io_uring_sqe] => Unit)(
        release: Int => IO[Unit]
    ): Resource[IO, Int] =
      Resource.makeFull[IO, Int](poll => poll(exec(prep)(release)))(release(_))

    private def exec(prep: Ptr[io_uring_sqe] => Unit)(
        release: Int => IO[Unit]
    ): IO[Int] =
      IO.cont {
        new Cont[IO, Int, Int] {
          def apply[F[_]](
              implicit F: MonadCancelThrow[F]
          ): (Either[Throwable, Int] => Unit, F[Int], IO ~> F) => F[Int] = {
            (resume, get, lift) =>
              F.uncancelable { poll =>
                val submit = IO.async_[(__u64, Poller)] { cb =>
                  ctx.accessPoller { poller =>
                    val sqe = poller.getSqe(resume)
                    prep(sqe)
                    cb(Right((sqe.user_data, poller)))
                  }
                }

                lift(submit)
                  .flatMap {
                    case (addr, submittedPoller) =>
                      F.onCancel(
                        poll(get),
                        lift(cancel(addr, submittedPoller)).ifM(
                          F.unit,
                          // If cannot cancel, fallback to get
                          get.flatMap { rtn =>
                            if (rtn < 0)
                              F.raiseError(new IOException(fromCString(strerror(-rtn))))
                            else lift(release(rtn))
                          }
                        )
                      )
                  }
                  .flatTap(e => F.raiseWhen(e < 0)(new IOException(fromCString(strerror(-e)))))
              }
          }
        }
      }

    private[this] def cancel(addr: __u64, submittedPoller: Poller): IO[Boolean] =
      IO.async_[Int] { cb =>
        ctx.accessPoller { currentPoller =>
          if (currentPoller eq submittedPoller) {
            val sqe = currentPoller.getSqe(cb)
            io_uring_prep_cancel64(sqe, addr, 0)
          } else {
            submittedPoller.enqueueCancelOperation(addr, cb)
          }
        }
      }.map(_ == 0)

    def registerFileDescriptor(
        fd: Int,
        reads: Boolean,
        writes: Boolean
    ): Resource[IO, FileDescriptorPollHandle] =
      Resource.eval {
        (Mutex[IO], Mutex[IO]).mapN { (readMutex, writeMutex) =>
          new FileDescriptorPollHandle {
            def pollReadRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
              readMutex.lock.surround {
                a.tailRecM { a =>
                  f(a).flatTap { r =>
                    if (r.isRight) IO.unit
                    else call(io_uring_prep_poll_add(_, fd, POLLIN.toUInt)).void
                  }
                }
              }

            def pollWriteRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
              writeMutex.lock.surround {
                a.tailRecM { a =>
                  f(a).flatTap { r =>
                    if (r.isRight) IO.unit
                    else call(io_uring_prep_poll_add(_, fd, POLLOUT.toUInt)).void
                  }
                }
              }
          }
        }
      }
  }

  final class Poller private[UringSystem] (
      ring: Ptr[io_uring],
      readEnd: CInt,
      writeEnd: CInt
  ) {
    private[this] var pendingSubmissions: Boolean = false
    private[this] var listeningWakeup: Boolean = false

    private[this] val callbacks: Set[Either[Throwable, Int] => Unit] =
      Collections.newSetFromMap(new IdentityHashMap)

    private[this] val cancelOperations
        : ConcurrentLinkedDeque[(__u64, Either[Throwable, Int] => Unit)] =
      new ConcurrentLinkedDeque

    private[this] val wakeupHandler: Either[Throwable, Int] => Unit = _ => ()

    private[UringSystem] def metrics(): PollerMetrics = PollerMetrics.noop

    private[UringSystem] def getSqe(
        cb: Either[Throwable, Int] => Unit
    ): Ptr[io_uring_sqe] = {
      pendingSubmissions = true
      val sqe = io_uring_get_sqe(ring)
      io_uring_sqe_set_data(sqe, cb)
      callbacks.add(cb)
      sqe
    }

    private[UringSystem] def enqueueCancelOperation(
        addr: __u64,
        cb: Either[Throwable, Int] => Unit
    ): Unit = {
      cancelOperations.add((addr, cb))
      wakeup()
    }

    private[UringSystem] def wakeup(): Unit = {
      val buf = stackalloc[Byte](1)
      !buf = 1.toByte
      unistd.write(writeEnd, buf, sizeof[Byte])
      ()
    }

    private[this] def armWakeup(): Unit = {
      val sqe = io_uring_get_sqe(ring)
      io_uring_sqe_set_data(sqe, wakeupHandler)
      io_uring_prep_poll_add(sqe, readEnd, POLLIN.toUInt)
      pendingSubmissions = true
      listeningWakeup = true
    }

    private[this] def drainCancelQueue(): Unit = {
      var op = cancelOperations.poll()
      while (op ne null) {
        val sqe = getSqe(op._2)
        io_uring_prep_cancel64(sqe, op._1, 0)
        op = cancelOperations.poll()
      }
    }

    private[UringSystem] def close(): Unit = {
      io_uring_queue_exit(ring)
      stdlib.free(ring.asInstanceOf[Ptr[Byte]])
      unistd.close(readEnd)
      unistd.close(writeEnd)
      ()
    }

    private[UringSystem] def needsPoll(): Boolean =
      pendingSubmissions || !callbacks.isEmpty() || !cancelOperations.isEmpty()

    private[UringSystem] def poll(nanos: Long): PollResult = {

      if (!listeningWakeup) armWakeup()
      drainCancelQueue()

      if (nanos == 0) {
        if (pendingSubmissions) {
          var rtn = io_uring_submit(ring)
          while (rtn == -EBUSY) {
            processReadyEvents()
            rtn = io_uring_submit(ring)
          }
          pendingSubmissions = false
        }
      } else {
        val timeoutSpec =
          if (nanos == -1) null
          else {
            val ts = stackalloc[__kernel_timespec]()
            ts.tv_sec = nanos / 1000000000L
            ts.tv_nsec = nanos % 1000000000L
            ts
          }

        val cqe = stackalloc[Ptr[io_uring_cqe]]()
        if (pendingSubmissions) {
          var rtn =
            io_uring_submit_and_wait_timeout(ring, cqe, 0.toUInt, timeoutSpec, null)
          while (rtn == -EBUSY) {
            processReadyEvents()
            rtn = io_uring_submit(ring)
          }
        } else {
          io_uring_wait_cqe_timeout(ring, cqe, timeoutSpec)
        }
        pendingSubmissions = false
      }

      val cqes = stackalloc[Ptr[io_uring_cqe]](MaxEvents)
      val filledCount = io_uring_peek_batch_cqe(ring, cqes, MaxEvents.toUInt).toInt

      if (filledCount > 0) {
        if (filledCount < MaxEvents) PollResult.Complete else PollResult.Incomplete
      } else PollResult.Interrupted
    }

    private[UringSystem] def processReadyEvents(): Boolean = {
      val cqes = stackalloc[Ptr[io_uring_cqe]](MaxEvents)
      val filledCount = io_uring_peek_batch_cqe(ring, cqes, MaxEvents.toUInt).toInt

      var i = 0
      val ptr = cqes
      while (i < filledCount) {
        val cqe = !(ptr + i.toLong)
        val cb = io_uring_cqe_get_data[Either[Throwable, Int] => Unit](cqe)
        if (cb eq wakeupHandler) {
          val buf = stackalloc[Byte](1)
          unistd.read(readEnd, buf, sizeof[Byte])
          listeningWakeup = false
        } else {
          val res = cqe.res
          cb(Right(res))
          callbacks.remove(cb)
        }
        i += 1
      }

      io_uring_cq_advance(ring, filledCount.toUInt)
      filledCount > 0
    }
  }

  private final val POLLIN: Int = 0x001
  private final val POLLOUT: Int = 0x004

  @nowarn212
  @link("uring")
  @extern
  private object uringNative {

    final val IORING_SETUP_SUBMIT_ALL = 1 << 7
    final val IORING_SETUP_COOP_TASKRUN = 1 << 8
    final val IORING_SETUP_TASKRUN_FLAG = 1 << 9
    final val IORING_SETUP_SINGLE_ISSUER = 1 << 12
    final val IORING_SETUP_DEFER_TASKRUN = 1 << 13

    type __u8 = CUnsignedChar
    type __u16 = CUnsignedShort
    type __s32 = CInt
    type __u32 = CUnsignedInt
    type __u64 = CUnsignedLongLong

    type __kernel_time64_t = CLongLong
    type __kernel_timespec = CStruct2[__kernel_time64_t, CLongLong]

    type io_uring = CStruct9[
      io_uring_sq,
      io_uring_cq,
      CUnsignedInt,
      CInt,
      CUnsignedInt,
      CInt,
      __u8,
      CArray[__u8, Nat._3],
      CUnsignedInt
    ]

    type io_uring_cq = CStruct12[
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[io_uring_cqe],
      CSize,
      Ptr[Byte],
      CUnsignedInt,
      CUnsignedInt,
      CArray[CUnsignedInt, Nat._2]
    ]

    type io_uring_cqe = CStruct3[__u64, __s32, __u32]

    type io_uring_sq = CStruct15[
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[io_uring_sqe],
      CUnsignedInt,
      CUnsignedInt,
      CSize,
      Ptr[Byte],
      CUnsignedInt,
      CUnsignedInt,
      CArray[CUnsignedInt, Nat._2]
    ]

    type io_uring_sqe = CStruct10[
      __u8,
      __u8,
      __u16,
      __s32,
      __u64,
      __u64,
      __u32,
      __u32,
      __u64,
      CArray[__u64, Nat._3]
    ]

    def io_uring_queue_init(
        entries: CUnsignedInt,
        ring: Ptr[io_uring],
        flags: CUnsignedInt
    ): CInt = extern

    def io_uring_queue_exit(ring: Ptr[io_uring]): Unit = extern

    @name("ce_io_uring_get_sqe")
    def io_uring_get_sqe(ring: Ptr[io_uring]): Ptr[io_uring_sqe] = extern

    def io_uring_submit(ring: Ptr[io_uring]): CInt = extern

    def io_uring_submit_and_wait_timeout(
        ring: Ptr[io_uring],
        cqe_ptr: Ptr[Ptr[io_uring_cqe]],
        wait_nr: CUnsignedInt,
        ts: Ptr[__kernel_timespec],
        sigmask: Ptr[Byte]
    ): CInt = extern

    def io_uring_wait_cqe_timeout(
        ring: Ptr[io_uring],
        cqe_ptr: Ptr[Ptr[io_uring_cqe]],
        ts: Ptr[__kernel_timespec]
    ): CInt = extern

    def io_uring_peek_batch_cqe(
        ring: Ptr[io_uring],
        cqes: Ptr[Ptr[io_uring_cqe]],
        count: CUnsignedInt
    ): CUnsignedInt = extern

    @name("ce_io_uring_cq_advance")
    def io_uring_cq_advance(ring: Ptr[io_uring], nr: CUnsignedInt): Unit = extern

    @name("ce_io_uring_prep_cancel64")
    def io_uring_prep_cancel64(
        sqe: Ptr[io_uring_sqe],
        user_data: __u64,
        flags: CInt
    ): Unit = extern

    @name("ce_io_uring_prep_poll_add")
    def io_uring_prep_poll_add(
        sqe: Ptr[io_uring_sqe],
        fd: CInt,
        pollmask: CUnsignedInt
    ): Unit = extern
  }

  private object uringNativeOps {

    import uringNative._

    def io_uring_sqe_set_data[A <: AnyRef](sqe: Ptr[io_uring_sqe], data: A): Unit =
      sqe.user_data = Intrinsics
        .castRawPtrToLong(
          Intrinsics.castObjectToRawPtr(data)
        )
        .toULong

    def io_uring_cqe_get_data[A <: AnyRef](cqe: Ptr[io_uring_cqe]): A =
      Intrinsics
        .castRawPtrToObject(
          Intrinsics.castLongToRawPtr(cqe.user_data.toLong)
        )
        .asInstanceOf[A]

    implicit final class io_uring_sqeOps(val sqe: Ptr[io_uring_sqe]) extends AnyVal {
      def user_data: __u64 = sqe._9
      def user_data_=(v: __u64): Unit = !sqe.at9 = v
    }

    implicit final class io_uring_cqeOps(val cqe: Ptr[io_uring_cqe]) extends AnyVal {
      def user_data: __u64 = cqe._1
      def res: __s32 = cqe._2
      def flags: __u32 = cqe._3
    }

    implicit final class __kernel_timespecOps(val ts: Ptr[__kernel_timespec]) extends AnyVal {
      def tv_sec: __kernel_time64_t = ts._1
      def tv_sec_=(v: __kernel_time64_t): Unit = !ts.at1 = v
      def tv_nsec: CLongLong = ts._2
      def tv_nsec_=(v: CLongLong): Unit = !ts.at2 = v
    }
  }
}
